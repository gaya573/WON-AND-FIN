# SMS Relay (Android) 안정성 재설계 계획

> 상태: **설계 문서 (구현 전)**  
> 범위: Android 앱 우선. 원더어드민 **금융사계정** 탭 · wondermain-spring 금융사/중계폰 관리 · Android 안전 수신까지 **E2E 설계 포함**.  
> 작성 기준일: 2026-09-11

---

## 0. 한 줄 결론

운영 흐름은 **원더어드민(금융사계정) → wondermain-spring(금융사·중계폰·세션 관리) → Android SMS Relay(수신만)** 이다.  
중계폰은 **SMS_RECEIVED → Room 선저장 → HTTPS POST → WorkManager 재전송**으로만 동작하고, 금융사 판별·OTP·점유(사용가능/중/불가)는 Spring이 담당한다.  
기존 채팅 Socket.IO / 경매 WebSocket / SSE는 SMS 경로에 쓰지 않는다.

---

## 1. 현재 코드베이스 조사 결과

### 1.1 Android SMS Relay 앱

| 항목 | 결과 |
|------|------|
| 워크스페이스 내 Android 프로젝트 | **없음** (`.kt` / `AndroidManifest` / `build.gradle(app)` 미존재) |
| 기존 SMS Relay 코드 | **없음 → 신규 프로젝트** |

### 1.2 기존 SMS 관련 (Spring)

| 기능 | 위치 | SMS Relay와의 관계 |
|------|------|-------------------|
| **Aligo 발송 SMS** | `wondermain-spring` shared SMS + `docs/aligo-sms-deployment.md` | **Outbound** (서버→고객). 중계폰 수신과 무관 |
| **딜러허브 SMS 인증 IP 차단** | `wonder-admin-front` `DealerHubPhoneVerificationBanManage` | 가입/인증 남용 차단. 중계폰 풀과 무관 |
| **1원 계좌인증** | `dealerhub.accountverification` + Apick | 은행 1원 이체 인증. **금융사 OTP SMS 중계와 별개** |
| **견적 PDF SMS 링크** | `DealerHubQuoteDocumentLinkService` 등 | Outbound 문서 링크 |

→ 금융사 인증 SMS를 수신해 Spring으로 올리는 **Inbound SMS Relay는 신규 도메인**이다.

### 1.3 실시간 채널 (소켓 등) — 재사용 여부

| 채널 | 구현 | 용도 | SMS Relay에 쓸까? |
|------|------|------|-------------------|
| **Socket.IO** | `dealer-hub` 클라이언트 → `VITE_CHAT_SOCKET_URL` (`deal-chat.p-e.kr`) | 딜러↔고객 채팅 | **No**. 채팅 전용 외부 게이트, 이 레포 Spring에 서버 없음. 백그라운드 SMS에 부적합 |
| **Spring WebSocket** | `DealerHubAuctionWebSocketHandler` | 중고차 경매 브로드캐스트 | **No**. 경매 이벤트 전용 |
| **SSE** | `ChatNotificationEmitter` | 채팅 알림 구독 | **No**. 브라우저 구독 모델, 단말→서버 전송 경로 아님 |
| **캐피탈 오픈채팅** | `CapitalRoomsPage` + `DealerHubCapitalRoomService` | 카카오 오픈채팅 링크/입장 로그 | **No**. 금융 OTP 중계와 무관. 상태(사용가능/중/불가) 모델도 없음 |

**결정:** SMS Relay는 **HTTPS REST만** 사용한다.

- 단말 → 서버: `POST /api/sms-relay/messages`, `POST /api/sms-relay/heartbeat`
- 서버 → 웹(어드민/딜러허브): 기존 REST + (필요 시) **짧은 폴링 또는 기존 SSE 확장**. Socket.IO/경매 WS에 SMS를 섞지 않음
- Foreground Service 24시간 상시 기동: **사용하지 않음**

### 1.4 기존 idempotency 패턴 (Spring 참고)

정산·서비스마켓·계좌인증 등에 `Idempotency-Key` / unique index 패턴이 이미 있다.  
SMS Relay도 동일하게 **헤더 `X-Idempotency-Key` + DB unique** 로 맞춘다. (구현은 Spring 단계에서)

---

## 2. 목표 아키텍처 (전체)

```
┌─────────────────────────────────────────────────────────────┐
│ wonder-admin-front                                          │
│  딜러허브 메뉴 > [금융사계정] 탭                              │
│  - 금융사 마스터 / 발신번호 패턴                               │
│  - 중계폰(Android) 등록·토큰·heartbeat                      │
│  - 계정 슬롯: 사용가능 / 사용중 / 사용불가                     │
└──────────────────────────┬──────────────────────────────────┘
                           │ Admin JWT HTTPS
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ wondermain-spring                                           │
│  금융사관리 Admin API  +  sms-relay ingest  +  financeauth  │
└───────┬───────────────────────────────┬─────────────────────┘
        │ dealer JWT                    │ device token HTTPS
        ▼                               ▼
┌───────────────────┐         ┌──────────────────────────────┐
│ dealer-hub        │         │ Android SMS Relay            │
│ 금융인증 사용/완료 │         │ 수신 → Room → POST only      │
└───────────────────┘         └──────────────────────────────┘
                                        ▲ SMS
                                   [금융사]
```

수신 경로(단말 내부):

```
SMS_RECEIVED → Room(PENDING) → HTTPS POST → ACK면 SENT
                              └ 실패 시 WorkManager
```

### 2.1 책임 분리 (확정)

| 레이어 | 하는 일 | 안 하는 일 |
|--------|---------|------------|
| **Android** | 수신, Room, HTTPS 전송, 상태 UI, 배터리 안내 | 금융사 판별, OTP, 점유 상태 |
| **Spring sms-relay ingest** | device token 검증, idempotent 저장, ACK, heartbeat | 업무 매칭 세부 |
| **Spring 금융사관리** | 금융사·중계폰·슬롯 CRUD, 토큰 발급/폐기, 감사 | SMS 본문 상시 노출 |
| **금융인증 모듈** | 파싱, OTP, 세션 매칭 | Room / WorkManager |
| **dealer-hub** | acquire(사용중) / complete(사용가능) | device token 취급 |
| **wonder-admin** | **금융사계정** 탭으로 운영 | 딜러 업무 화면 대체 |

### 2.2 Spring 3갈래 + 어드민 탭

1. **원더어드민 — 금융사계정** (`/admin/dealerhub-finance-accounts`)  
2. **딜러허브 — 금융인증 사용 UI**  
3. **금융인증 엔진 — OTP/세션**  

Android 단계에서는 서버 API를 **계약(stub 가능)** 으로만 고정한다.

---

## 2A. 원더어드민 「금융사계정」 탭 설계

### 2A.1 메뉴 배치 (기존 AdminLayout 맞춤)

현재 딜러허브 그룹: `memberSecurity` / `adminOperations` / `financeOperations` / `usedCarOperations` / `services`

| 항목 | 값 |
|------|-----|
| 경로 | `/admin/dealerhub-finance-accounts` |
| 라벨 | **금융사계정** |
| 그룹 | `financeOperations` (또는 신규 `financeAuth`) |
| 페이지 | `DealerHubFinanceAccountManage.jsx` |
| permission | `all` + `dealerhub-finance-account` (토큰 발급은 ADMIN/CEO만) |
| 수정 파일 | `AdminLayout.jsx`, `menuPermissions.js`, `App.jsx` |

기존과 분리:

| 메뉴 | 역할 |
|------|------|
| SMS 인증 IP 차단 | 가입/인증 남용 IP |
| 캐피탈 오픈채팅 (딜러허브) | 카카오 오픈채팅 링크 |
| **금융사계정 (신규)** | 중계폰·OTP 슬롯·금융사 마스터 |

### 2A.2 화면 서브탭

1. **중계폰** — Android 단말 등록/heartbeat/토큰  
2. **금융사** — 마스터·발신번호·(서버측) OTP 패턴  
3. **계정 슬롯** — 사용가능 / 사용중 / 사용불가  
4. **수신 로그(마스킹)** — ingest 요약, 본문 기본 숨김  
5. **감사** — 토큰·상태 변경 이력  

#### 중계폰 탭

| 컬럼 | 설명 |
|------|------|
| deviceId | `relay-01` |
| 표시명 | 책상/SIM 라벨 |
| 번호(마스킹) | `010-****-1234` |
| heartbeat | 시각 + stale 배지 |
| 앱 헬스 | SMS권한 / Battery Unrestricted (heartbeat) |
| 슬롯 상태 | AVAILABLE / IN_USE / UNAVAILABLE |
| 현재 사용자 | IN_USE 시 딜러 ID 마스킹 |
| 동작 | 등록, **토큰 1회 발급**, 폐기, 사용불가, 복구, 강제 반환 |

경고:

- heartbeat 지연 → **「SMS 중계폰 연결 확인 필요」**
- `batteryUnrestricted=false` → 노란 경고
- 장기 무heartbeat → Force Stop 의심(빨간)

#### 금융사 탭

- code / 표시명 / 발신번호 목록 / 활성  
- OTP 정규식은 **서버만** 보관 (Android APK에 넣지 않음)

#### 계정 슬롯 탭 (카드 UI)

```
relay-01  ● 사용가능     heartbeat 30초 전
relay-02  ● 사용중       dealer dh_*** / 14:02  [강제 반환]
relay-03  ● 사용불가     heartbeat 끊김      [사용가능 복구]
```

### 2A.3 Admin API (`wondermain-spring`)

Base: `/api/admin/dealerhub-finance-accounts`  
인증: 기존 Admin JWT

| Method | Path | 용도 |
|--------|------|------|
| GET | `/devices` | 목록+상태 |
| POST | `/devices` | 등록 |
| PATCH | `/devices/{id}` | 메타 수정 |
| POST | `/devices/{id}/tokens` | token **plaintext 1회** 발급 |
| DELETE | `/devices/{id}/tokens/current` | 폐기 → 단말 401 |
| POST | `/devices/{id}/unavailable` | 사용불가 |
| POST | `/devices/{id}/available` | 복구 |
| POST | `/devices/{id}/force-release` | IN_USE 강제 종료 |
| CRUD | `/companies` | 금융사 마스터 |
| GET | `/slots` | 슬롯 요약 |
| GET | `/ingest-logs` | 마스킹 로그 |
| GET | `/audit-logs` | 감사 |

목록 API에 token 평문·SMS 본문·OTP **금지**.

### 2A.4 DB 초안

```
finance_relay_device
  device_id, display_name, phone_masked, status,
  token_hash, last_heartbeat_at, last_sms_at,
  battery_unrestricted, sms_permission_ok, unavailable_reason

finance_company / finance_company_sender
finance_auth_session   -- acquire/complete
finance_sms_ingest     -- unique_key UNIQUE, device_id, sender, body(제한), matched_session_id
finance_admin_audit
```

### 2A.5 안전 체인: 어드민 → Spring → Android

```
어드민: 중계폰 등록
  → Spring device row (AVAILABLE, token 없음)
어드민: 토큰 발급
  → Spring random token + hash 저장
  → plaintext는 응답 1회만 (재조회 불가)
운영자: Android Setup에 수동 입력 → EncryptedSharedPreferences
Android: Authorization Bearer 로 ingest/heartbeat
토큰 폐기/불일치 → 401/403 → Android ERROR_AUTH (무한재시도 금지)
  → 어드민 경고 / UNAVAILABLE 가능
```

규칙:

1. token 평문 DB·로그·목록 저장 금지  
2. Android 하드코딩 금지, HTTPS only  
3. 딜러허브는 token을 받지 않음 (세션 API만)  
4. 어드민 본문/OTP 기본 비표시  
5. 토큰 발급 권한과 조회 권한 분리  

### 2A.6 딜러허브 연결

1. 사용가능 슬롯 조회  
2. `acquire` → **사용중**  
3. 해당 device SMS → 세션 OTP 매칭  
4. `complete` → **사용가능**  
5. 예외만 어드민 강제 반환/사용불가  

---

## 3. 중계폰 / 금융인증 상태 모델 (서버)

Android는 상태를 계산하지 않는다. 서버·어드민·딜러허브만 사용.

```
사용가능 (AVAILABLE)
    │  딜러허브 금융인증 시작 (acquire)
    ▼
사용중 (IN_USE)  ←── 해당 슬롯 SMS만 세션 소비
    │  사용완료 (complete) 또는 어드민 강제 반환
    ▼
사용가능 (AVAILABLE)

사용불가 (UNAVAILABLE)
  - 어드민 수동
  - heartbeat stale
  - token 폐기 / ERROR_AUTH
  - Force Stop 의심 (운영 표시)
  - 배터리 미제외 지속(선택)
```

규칙 초안:

- 슬롯당 **IN_USE 세션 1개**만 (동시 점유 방지)
- 사용중 SMS → ingest 후 **현재 세션**에만 매칭
- 사용가능 시 도착 SMS 정책은 서버 결정 (Android는 전부 전송)
- 사용완료 = session close → AVAILABLE

---

## 4. Android 앱 설계 (이번 구현 범위)

### 4.1 신규 프로젝트

```
sms-relay-android/          # 제안 경로 (워크스페이스 루트)
  app/
    src/main/java/.../smsrelay/
      receiver/
        SmsReceiver.kt
        BootReceiver.kt
      data/
        SmsEntity.kt
        SmsDao.kt
        SmsDatabase.kt
      network/
        SmsApi.kt
        ApiClient.kt
        dto/
      worker/
        SmsUploadWorker.kt
        PendingSmsWorker.kt
        HeartbeatWorker.kt
      repository/
        SmsRepository.kt
      security/
        DeviceTokenStore.kt
        SecurePrefs.kt
      ui/
        MainActivity.kt
        StatusViewModel.kt
        SetupActivity.kt          # 최초 권한/토큰/배터리 설정
      sync/
        InboxSyncManager.kt       # READ_SMS 가능 시
    AndroidManifest.xml
  README.md
```

기술 스택:

- Kotlin, minSdk 26+, targetSdk 35/36 (15/16 대비)
- Room, WorkManager, OkHttp (또는 Retrofit)
- EncryptedSharedPreferences (+ Android Keystore)
- Cleartext traffic **차단** (`usesCleartextTraffic=false`)

### 4.2 핵심 흐름

```
SMS_RECEIVED
  → SmsReceiver (goAsync 짧게)
  → uniqueKey 생성
  → Room INSERT IGNORE / OnConflict IGNORE (PENDING)
  → 신규 insert면 WorkManager unique work enqueue (즉시 시도)
       또는 Repository.tryUploadNow (짧은 네트워크) 후 실패 시 Worker
  → SENT만 ACK 성공 시
```

실패/복구:

| 상황 | 동작 |
|------|------|
| 네트워크/5xx/timeout/DNS | Room 유지 + `Result.retry()` + exponential backoff |
| 401/403 | `ERROR_AUTH`, 자동 무한 재시도 중단, UI 표시 |
| 400 | `ERROR_PAYLOAD`, 재시도 중단(또는 수동만) |
| 부팅 | `BOOT_COMPLETED` → PendingSmsWorker |
| 앱 재실행 | MainActivity/Application에서 Pending enqueue |
| Inbox 누락 | lastSyncTime 이후 조회 → 없는 것만 INSERT |

### 4.3 Room 스키마

| 필드 | 설명 |
|------|------|
| `id` | PK (local) |
| `uniqueKey` | deterministic hash, **UNIQUE INDEX** |
| `sender` | 발신번호 |
| `message` | 본문 전체 (서버가 파싱) |
| `receivedAt` | epoch ms |
| `status` | `PENDING` / `SENDING` / `SENT` / `FAILED` / `ERROR_AUTH` / `ERROR_PAYLOAD` |
| `retryCount` | |
| `lastAttemptAt` | |
| `serverMessageId` | ACK의 messageId |
| `httpLastStatus` | 디버그용 |

`uniqueKey` 권장:

```text
SHA-256( normalize(sender) + "\n" + message + "\n" + receivedAtBucket )
```

- `receivedAt`은 초 단위 버킷 또는 Telephony timestamp 그대로 (기기마다 동일성 주의)
- Inbox 재조회와 Broadcast가 타임스탬프가 달라도 **본문+발신+근접시간** 충돌 시 unique로 흡수할 수 있게 문서화
- 서버로 보내는 idempotency key = `uniqueKey` (헤더 `X-Idempotency-Key`)

### 4.4 네트워크 계약 (Android ↔ Spring ingest)

**POST `/api/sms-relay/messages`**

Request headers:

- `Authorization: Bearer <deviceToken>` 또는 `X-Device-Token`
- `X-Idempotency-Key: <uniqueKey>`
- `Content-Type: application/json`

Body:

```json
{
  "uniqueKey": "...",
  "sender": "15881234",
  "message": "...",
  "receivedAt": "2026-09-11T13:42:31.123+09:00",
  "deviceId": "relay-phone-01"
}
```

ACK (SENT 조건):

```json
{
  "success": true,
  "messageId": "srv-...",
  "receivedAt": "2026-09-11T13:42:32.001+09:00"
}
```

`success != true` 또는 body 파싱 실패 → SENT 처리하지 않음.

**POST `/api/sms-relay/heartbeat`**

```json
{
  "deviceId": "relay-phone-01",
  "appVersion": "1.0.0",
  "pendingCount": 0,
  "failedCount": 0,
  "smsPermission": true,
  "batteryUnrestricted": true,
  "lastSmsAt": "..."
}
```

주기: WorkManager Periodic (예: 15분 최소 제약 인지). **정확한 간격 보장 불가** → 서버는 stale heartbeat로 운영 경고.

**POST `/api/sms-relay/ping`** (연결 테스트용, 선택)

### 4.5 Manifest / 권한

필수:

- `RECEIVE_SMS`
- `RECEIVE_BOOT_COMPLETED`
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- (가능 시) `READ_SMS` — Inbox 재동기화

런타임:

- Android 6+ SMS 권한 요청 (Setup/Main)
- 알림 권한: WorkManager 성공 알림을 쓰지 않으면 불필요. 상태 UI만이면 POST_NOTIFICATIONS 최소화

Receiver:

- `SmsReceiver` — `android.provider.Telephony.SMS_RECEIVED` (exported=true, permission `BROADCAST_SMS` 권장)
- `BootReceiver` — `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED`(선택, credential-encrypted 전까지 Room 접근 주의)

보안:

- device token은 코드 하드코딩 금지 → Setup에서 1회 입력 또는 QR → EncryptedSharedPreferences
- HTTPS only

### 4.6 Doze / 배터리 / Force Stop

| 위험 | 대응 |
|------|------|
| 화면 OFF 단기 | SMS_RECEIVED로 프로세스 기동 → Room 저장 OK |
| Doze 장기 | 전송·Worker **지연 가능**. Battery Optimization **제외(Unrestricted)** 필수 UI |
| OEM 추가 절전 | 설정 화면 안내 문구 |
| Process death | 다음 SMS / Boot / 앱 실행으로 복구. 메모리 싱글톤 의존 금지 |
| **Force Stop** | 우회 불가(특히 Android 15+). 상태 점검 UI + 운영 수칙(강제종료 금지, 상시 충전) |

Foreground Service 상시: **비채택**.

### 4.7 UI (최소)

```
SMS Relay
────────────────
서버 상태       ● 정상 / ● 오류 / ● 미설정
SMS 권한        ● 허용
배터리 최적화   ● 제외됨
마지막 SMS      HH:mm:ss
마지막 전송     HH:mm:ss
대기중          N건
실패            N건
인증오류        N건

[서버 연결 테스트]
[대기 메시지 재전송]
[최근 SMS 동기화]
[배터리 설정 열기]
[상태 점검]          ← Force Stop / 권한 / 토큰 / Unrestricted 체크리스트
```

### 4.8 로깅

금지: SMS 원문, OTP, device token 전체  
허용: uniqueKey 앞 8자, sender 마스킹(`1588****`), status, HTTP code, retryCount

### 4.9 READ_SMS / Inbox 제한 메모 (코드·README에 명시)

- 기본 SMS 앱이 아닌 앱의 `READ_SMS` / `RECEIVE_SMS`는 Play 정책상 제한이 큼
- **회사 전용 사이드로드 / 관리 단말** 전제로 설계
- Play 배포 불가 가능성 → README에 “내부 배포 전용” 명시
- Android 버전별 동작은 실기기 매트릭스로 검증 (에뮬레이터 SMS는 제한적)

---

## 5. Android 구현 단계 (코딩 순서)

### Phase A — 골격 (1)

1. `sms-relay-android` Android Studio 프로젝트 생성  
2. 패키지 골격 + Manifest 권한/Receiver 골격  
3. Room Entity/Dao/DB  
4. DeviceTokenStore (EncryptedSharedPreferences)  
5. Setup + Main 상태 화면 뼈대  

### Phase B — 수신·저장 (2)

1. `SmsReceiver` + uniqueKey + PENDING 저장  
2. 중복 insert 무시 검증  
3. Logcat 마스킹 로거  

### Phase C — 전송 (3)

1. ApiClient (timeout, HTTPS)  
2. SmsRepository: 즉시 POST + ACK → SENT  
3. 실패 분류 (retry / ERROR_AUTH / ERROR_PAYLOAD)  
4. `SmsUploadWorker` + exponential backoff + `NetworkType.CONNECTED`  

### Phase D — 복구 (4)

1. `BootReceiver` → PendingSmsWorker  
2. 앱 시작 시 pending flush  
3. Inbox sync (`lastSyncTime`) + 수동 “최근 SMS 동기화”  
4. HeartbeatWorker  

### Phase E — 운영 UX (5)

1. 배터리 Unrestricted 딥링크  
2. 상태 점검 체크리스트  
3. README: ADB 테스트 시나리오 A–K  

### Phase F — 서버·어드민 (Android 안정화 후)

1. Spring `sms-relay` ingest + ACK + heartbeat  
2. Spring 금융사관리 Admin API + DB  
3. wonder-admin **금융사계정** 탭  
4. financeauth 세션 + dealer-hub acquire/complete  

**우선순위:** Phase A~E(Android) → Phase F1(ingest stub) → F2~F4(어드민·금융사관리·딜러허브).

---

## 6. 테스트 계획 (A–K) + ADB

| ID | 시나리오 | 기대 | 비고 |
|----|----------|------|------|
| A | 앱 포그라운드 SMS | Room→SENT | |
| B | 홈으로 백그라운드 | 동일 | |
| C | 화면 OFF | 수신·저장 OK | |
| D | 30분+ 화면 OFF | Doze면 전송 지연 가능, Room 유실 없음 | Unrestricted 권장 |
| E | 프로세스 kill | 다음 SMS로 복구 | `am force-stop`은 F와 다름에 주의 |
| F | 재부팅 | Boot 후 pending 재전송 | |
| G | 데이터 OFF→SMS→ON | pending 후 SENT | |
| H | 서버 DOWN→복구 | Room 보존 후 SENT | |
| I | 동일 SMS 이중 처리 | 서버 1회 / Room 1행 | |
| J | Doze 강제 | 전송 지연 관찰 | dumpsys deviceidle |
| K | 배터리 최적화 ON/OFF | 지연 차이 기록 | |

README에 넣을 ADB 예시:

```bash
# 테스트 SMS (에뮬레이터)
adb emu sms send 15881234 "인증번호 [123456] 테스트"

# 프로세스 종료 (시스템 kill 유사 — force-stop과 구분)
adb shell am kill com.company.smsrelay

# Force Stop (복구 불가 구간 — 별도 시나리오)
adb shell am force-stop com.company.smsrelay

# Doze
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce

# 배터리 최적화 목록 확인
adb shell dumpsys deviceidle whitelist

# 앱 데이터/로그
adb logcat -s SmsRelay
```

실기기 금융사 SMS는 스테이징 번호로만 검증.

---

## 7. Architecture Review 체크리스트 (구현 완료 후)

- [ ] Manifest Receiver로 SMS 수신 (Activity 비의존)
- [ ] Room 선저장 후 네트워크
- [ ] unique index + X-Idempotency-Key
- [ ] ACK JSON 검증 후에만 SENT
- [ ] 5xx/timeout 재시도, 401/403·400 분리
- [ ] WorkManager CONNECTED + exponential backoff
- [ ] BOOT_COMPLETED pending flush
- [ ] Inbox sync + 정책 제한 README
- [ ] Cleartext 금지, token 비하드코딩
- [ ] 로그에 본문/OTP 없음
- [ ] Battery Unrestricted UX
- [ ] Force Stop 한계 UI/운영 문서
- [ ] Android 13–16 background/SMS 권한 매트릭스 기록
- [ ] 기존 Socket.IO/경매 WS/SSE와 혼선 없음
- [ ] 업무 로직(금융사/OTP/매칭) Android 부재
- [ ] 어드민 토큰 평문 1회만 / DB hash만
- [ ] 어드민 SMS 본문·OTP 기본 비노출
- [ ] 딜러허브에 device token 미전달
- [ ] AVAILABLE/IN_USE/UNAVAILABLE + 강제 반환 감사 로그
- [ ] 금융사계정 메뉴가 캐피탈오픈채팅·SMS IP차단과 분리

---

## 8. 후속 Spring / 프론트 작업 범위

### 8.1 Spring 패키지 제안

```
.../smsrelay/              # ingest + heartbeat (device token)
.../financeauth/           # 세션 AVAILABLE|IN_USE|UNAVAILABLE, OTP
.../wondergoodlife/admin/  # /api/admin/dealerhub-finance-accounts/*
```

### 8.2 wonder-admin

- 메뉴: 딜러허브 → **금융사계정** (`/admin/dealerhub-finance-accounts`)
- 페이지: 중계폰 / 금융사 / 슬롯 / 마스킹 로그 / 감사
- permission: `dealerhub-finance-account` (+ 토큰 발급 elevated)

### 8.3 dealer-hub

- 금융인증: 사용가능 목록 → 사용중(acquire) → OTP 표시 → 사용완료
- device token / Room / SMS 원문 저장 없음

### 8.4 기존 시스템과 경계

- Aligo outbound 분리
- accountverification(1원) 분리
- capital openchat 분리
- SMS IP ban 메뉴 분리

---

## 9. 리스크와 운영 수칙

1. **Force Stop / Android 15 stopped 상태** — 앱으로 우회 불가 → 중계폰 운영 규칙  
2. **Doze** — Unrestricted + 상시 충전  
3. **Play 정책** — 내부 APK 배포  
4. **uniqueKey 충돌** — 동일 초·동일 본문 재수신 시 의도적 1회 처리 (금융 OTP는 보통 재발송 본문 다름)  
5. **서버 장애** — Room 보존이 단일 진실원 (단말)

---

## 10. 다음 액션

구현 현황 (2026-09-11):

- [x] Android `sms-relay-android` 골격
- [x] Spring `/api/sms-relay/*` ingest + heartbeat + ping
- [x] Spring `/api/admin/dealerhub-finance-accounts/*`
- [x] Spring `/api/dealerhub/finance-auth/*` (acquire/complete)
- [x] wonder-admin **금융사계정** 메뉴/페이지
- [x] dealer-hub `/finance-auth`, `/m/finance-auth`

운영 순서:

1. 어드민에서 중계폰 등록 → 토큰 발급  
2. Android Setup에 HTTPS URL / deviceId / token 입력  
3. 딜러허브 금융인증에서 사용 시작 → SMS → OTP → 사용완료  

---

## 부록 A. “소켓을 쓰지 않는” 이유 요약

- SMS 유실 방지의 핵심은 **로컬 Room + 재시도**이지 실시간 소켓 연결 유지가 아님  
- 채팅 Socket.IO는 별도 호스트·인증·세션 모델  
- 경매 WS는 브로드캐스트 중심  
- SSE는 서버→브라우저  
- 상시 소켓/FGS는 Doze·백그라운드 정책과 충돌 크고 운영 복잡  

→ **이벤트성 HTTPS + WorkManager**가 요구사항과 Android 정책에 가장 맞음.

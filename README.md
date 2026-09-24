# SMS Relay Android

## 1.0.3 연결 상태 표시

- 첫 화면에 **연결 전 / 권한 확인 중 / 연결 중 / 연결됨 / 연결 실패**를 큰 글자와 상태 아이콘으로 표시합니다.
- 실제 요청에 맞춰 **휴대폰 등록 중 → 서버 응답 확인 중 → 연결 상태 전송 중** 진행 상황을 보여줍니다. 기존 등록 기기는 등록 단계를 건너뜁니다.
- 권한이 거부되거나 서버 연결에 실패하면 원인과 다음 행동을 안내하고, 화면을 다시 열어도 실패 표시를 지우지 않습니다. 권한 확인·서버 요청 중에는 중복 연결을 막습니다.
- 버전 `1.0.3`, `versionCode=4`. 기존 릴리스 키로 서명해 이전 앱 위에 업데이트합니다.

## 1.0.2 원클릭 연결

- 첫 화면은 **환경설정 / 연결** 두 버튼만 제공합니다. 주소·기기 이름·비밀번호·토큰 입력은 없습니다.
- **연결 한 번** → 필요한 Android SMS 권한 허용 → 자동 등록·서버 확인·상태 보고 → 연결됨. 권한 허용 후 연결을 다시 누를 필요가 없습니다.
- 알림 권한은 별도 요청하며, 알림 거부나 배터리 제한 때문에 연결을 막지 않습니다. 배터리·자동 실행은 환경설정에서 나중에 조정할 수 있습니다.
- 신규 기기의 UUID와 랜덤 비밀값은 요청 전에 암호화 저장합니다. 네트워크가 끊겨도 같은 기기로 재시도합니다. 관리자 승인이나 공용 비밀번호는 사용하지 않습니다.
- 등록·ping·heartbeat의 성공을 모두 확인한 뒤에만 신규 기기의 문자 전송 작업을 시작합니다.
- 기존 기기 ID·토큰·문자 DB·동기화 위치를 유지하고 서버 주소만 `https://api.dealerhub.co.kr`로 정규화합니다. 기존 연결이 폐기·중지된 경우 새 기기 ID로 우회 등록하지 않습니다.
- 버전 `1.0.2`, `versionCode=3`. 기존 릴리스 키로 서명한 설치 파일은 이전 앱 위에 업데이트합니다.

현재 앱은 수신 SMS 전체를 관리자 화면으로 전달합니다. 금융사 판별은 서버에서 수행합니다.

## 이전 1.0.1 변경 기록

- 기본 서버: `https://api.dealerhub.co.kr` (기존 설치의 저장된 주소는 설정에서 확인).
- 최초 등록 시점부터 자동 받은편지함 복구를 시작하며, 등록 이전의 전체 문자함을 자동 수집하지 않습니다.
- 조회 도중 도착한 문자는 다음 동기화에서 복구하고, 최근 10분 수동 조회는 자동 복구 위치를 건너뛰지 않습니다.
- 정상 재등록 후 인증 오류 문자를 다시 전송합니다. 복구 큐가 50건을 넘으면 다음 작업으로 이어갑니다.
- 토큰은 암호화 저장만 허용합니다. 이전 평문 저장 정보는 암호화 저장에 성공한 후 제거합니다.
- `versionCode=2`, `versionName=1.0.1`. 기존 릴리스 키를 사용해야 1.0.0 위에 업데이트할 수 있습니다.

검수: `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`.
아래 `release/sms-relay-1.0.0.apk` 설명은 저장소에 남아 있는 이전 설치 파일입니다.

회사 전용 금융사 인증 SMS 중계 앱입니다.  
**수신 → Room 선저장 → HTTPS POST → 실패 시 WorkManager 재전송**만 담당하며, 금융사 판별·OTP·사용자 매칭은 Spring 서버에서 처리합니다.

내부 사이드로드/관리 단말 전용입니다. `RECEIVE_SMS` / `READ_SMS`는 Play 정책과 충돌할 수 있습니다.

## 요구사항

- Android Studio Ladybug+ / JDK 17
- minSdk 26, targetSdk 36
- HTTPS 서버 (`/api/sms-relay/messages`, `/heartbeat`, `/ping`)

## 열기

1. Android Studio에서 `sms-relay-android` 폴더 Open
2. Gradle Sync
3. 실기기 또는 에뮬레이터에 Run

## 설치 (중계폰에서 바로 내려받기)

배포용 APK가 저장소에 함께 있습니다. 중계폰 브라우저에서 아래를 열어 받으면 됩니다.

- `release/sms-relay-1.0.0.apk` → GitHub 파일 화면의 **Download** 버튼

| | |
|---|---|
| 버전 | 1.0.0 (versionCode 1) |
| 크기 | 7.02 MB |
| SHA-256 | `9FE2731C834B571D7274E02188149B7953024CEB1313A8080115009FDC1D7A4E` |
| 서명 | `CN=SMS Relay, O=WonderGoodlife` |

받은 뒤 파일관리자에서 APK를 실행합니다. 처음 설치할 때는 **알 수 없는 앱 설치 허용**을 묻습니다.

최신 1.0.3 설치 후에는 첫 화면에서 **연결**을 누르고 Android가 요청하는 SMS 권한을 허용하면 자동 연결됩니다. 환경설정은 권한을 다시 허용하거나 배터리·자동 실행을 조정할 때 사용합니다.

## APK 다시 만들기

서명된 릴리스 APK를 만들어 중계폰으로 옮기는 방식입니다.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
cd sms-relay-android
.\gradlew.bat assembleRelease
```

결과물: `app/build/outputs/apk/release/app-release.apk`

중계폰으로 옮기는 방법은 아래 중 하나입니다.

- USB 연결 후 파일 복사 → 폰 파일관리자에서 APK 실행
- `adb install -r <apk경로>`
- 사내 공유 스토리지에 올린 뒤 폰에서 내려받기 (외부 공개 링크 금지)

폰에서 처음 설치할 때는 **알 수 없는 앱 설치 허용**이 필요합니다.
복사 후 SHA-256을 비교해 파일이 온전한지 확인하세요.

```powershell
Get-FileHash .\dist\sms-relay-v1.0.0-<날짜>.apk -Algorithm SHA256
```

### 서명 키

- `smsrelay-release.jks` + `keystore.properties` 는 **git에 올리지 않습니다**(`.gitignore` 처리됨).
- 두 파일을 잃어버리면 기존 설치본 위에 업데이트할 수 없고, 앱을 지웠다 다시 깔아야 합니다. 안전한 곳에 백업하세요.
- 버전을 올릴 때는 `app/build.gradle.kts` 의 `versionCode`/`versionName` 을 증가시킵니다.

## 초기 설정

1. 앱 실행 → **연결**
2. Android SMS 권한 요청 허용 → 자동으로 연결 완료
3. 알림 권한은 별도 선택
4. 필요하면 **환경설정**에서 배터리 제한 해제·자동 실행 허용

## 흐름

```
SMS_RECEIVED (Manifest Receiver)
  → Room PENDING (uniqueKey dedupe)
  → SmsUploadWorker (NetworkType.CONNECTED)
  → ACK success+messageId 일 때만 SENT
  → 실패 시 FAILED + retry / 401·403 ERROR_AUTH / 400 ERROR_PAYLOAD
```

부팅: `BOOT_COMPLETED` → Pending flush + Heartbeat periodic(15분, 정확 간격 비보장)

## 운영 주의

| 상황 | 결과 |
|------|------|
| 홈/화면 OFF | SMS 수신 가능 |
| Process kill | 다음 SMS로 복구 |
| 재부팅 | BootReceiver 복구 |
| 네트워크/서버 DOWN | Room 보존 후 재전송 |
| **Force Stop** | 복구 불가 — 앱 재실행 필요 |
| Doze | 전송·heartbeat 지연 가능 → Unrestricted 필수 |

강제 종료 금지, 상시 충전, 배터리 Unrestricted를 권장합니다.

## ADB 테스트

```bash
# 에뮬레이터 테스트 SMS
adb emu sms send 15881234 "인증번호 [123456] 테스트"

# 프로세스 kill (Force Stop 아님)
adb shell am kill com.windergoodlife.smsrelay

# Force Stop (복구 불가 구간)
adb shell am force-stop com.windergoodlife.smsrelay

# Doze
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce

# 배터리 whitelist
adb shell dumpsys deviceidle whitelist

# 로그 (본문/OTP 미출력 설계)
adb logcat -s SmsRelay
```

### 시나리오 체크리스트

- A 포그라운드 SMS
- B 백그라운드
- C 화면 OFF
- D 장시간 OFF / Doze
- E process kill
- F 재부팅
- G 데이터 OFF→ON
- H 서버 DOWN→복구
- I 중복 SMS
- J Doze 강제
- K 배터리 최적화 ON/OFF

## 패키지

```
receiver/  SmsReceiver, BootReceiver
data/      Room
network/   Retrofit + DTOs
worker/    Upload / Pending / Heartbeat
repository/
security/  EncryptedSharedPreferences token
ui/        MainActivity, SetupActivity, StatusViewModel
sync/      InboxSyncManager (READ_SMS)
```

## 서버 계약 (요약)

- `POST /api/sms-relay/messages` + `X-Idempotency-Key` + Bearer device token  
  ACK: `{ "success": true, "messageId": "...", "receivedAt": "..." }`
- `POST /api/sms-relay/heartbeat`
- `POST /api/sms-relay/ping`

Cleartext HTTP 금지. 토큰 하드코딩 금지.

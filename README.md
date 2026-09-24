# SMS Relay Android

## 1.0.10 SMS·MMS·삼성 채팅 수신 통합

- 일반 SMS와 별도 저장되는 MMS, 지원 삼성 단말의 채팅(RCS) 수신 대화를 함께 전달합니다. 삼성의 내보낸 `im` 제공자가 기존 `READ_SMS` 권한을 사용하는 경우에만 연결하며, 다른 단말에는 SMS·MMS가 유지됩니다.
- 요청된 전체 과거분을 처음 한 번 확인한 뒤 소스별 마지막 처리 ID 이후만 복구합니다. 기존 SMS 키·전송 완료 상태·기기 연결 정보는 보존합니다. MMS·채팅·채팅 첨부는 서로 다른 키를 사용합니다.
- `sms`, `mms`, `im`, `mms-sms` 변경 알림을 합쳐 제한된 페이지로 수집합니다. 앱 시작·재연결·부팅·네트워크 복구도 같은 증분 경로를 사용합니다. 불완전한 MMS는 완료된 구간까지만 기록하고 재시도하며 다른 소스는 계속 처리합니다.
- 수신 내용과 발신번호를 전달하며 발신 문자·초안·숨김 채팅은 제외합니다. 사진·동영상·파일의 바이너리는 전송하지 않고 첨부가 있었다는 설명을 표시합니다.
- 로그 복사에 SMS/MMS/삼성 채팅별 조회 건수·최신 수신 시각·복구 지점 관계를 추가했습니다. 번호·본문·키·토큰은 진단 로그에 포함하지 않습니다.
- 버전 `1.0.10`, `versionCode=11`, 기존 인증서 유지. 앱 삭제 없이 덮어 업데이트합니다.
- 로컬 검증: unit 151개 통과, release lint 오류 0개·경고 53개, 서명 APK 검증 통과. 단말/서버 실제 수집 결과는 별도 배포 기록에서 확인합니다.

## 1.0.6 전송 완료 문자 중복 전달 방지

- 기본 연결을 `https://chat.dealerhub.co.kr`의 Node 모바일 API로 전환합니다. 기존 기본 주소 `https://api.dealerhub.co.kr`만 자동 전환하며 별도 HTTPS 주소는 유지합니다. 기기 ID·비밀값·문자 uniqueKey는 바꾸지 않습니다.
- **Node 모바일 API 운영 배포 전에는 이 APK를 배포하지 않습니다.** 기존 `/api/sms-relay/connect`, `/ping`, `/heartbeat`, `/messages` 요청·응답 계약과 `DEVICE_NOT_REGISTERED` 처리를 유지하고, Node는 영구 저장을 완료한 뒤 성공 ACK를 반환해야 합니다.
- 개별 문자 작업과 미전송 복구 작업이 같은 문자를 동시에 서버에 보내지 않도록 전송부터 성공 기록까지 순서대로 처리합니다. 뒤에서 기다린 작업은 저장된 `SENT`를 확인하고 요청을 생략합니다.
- 앱 재시작·재연결·받은편지함 재조회에서도 기존 성공 기록과 동일한 중복 방지 키를 유지합니다. 응답을 받지 못한 요청은 같은 키로 재시도하므로 서버의 중복 방지 처리가 함께 적용됩니다.
- 재연결 시 첫 전환은 기존 시간 체크포인트부터 이어 읽고, 이후에는 휴대폰 문자함의 마지막 처리 ID보다 새로 생긴 행만 읽습니다. 수신 시각이 같거나 늦게 저장된 문자도 누락하지 않으며, 해당 구간이 Room에 저장된 뒤에만 ID·시간을 함께 갱신합니다.
- 기존 문자 기록과 대기열을 삭제하거나, 동기화 시점을 초기화하거나, 과거 문자함 전체를 다시 수집하지 않습니다.
- 버전 `1.0.6`, `versionCode=7`, 기존 인증서 유지.

## 1.0.5 미등록 휴대폰 자동 연결

- 서버가 ping의 `410`과 정확한 `message=DEVICE_NOT_REGISTERED` 응답으로 미등록 상태를 확인하면 **등록 → ping → heartbeat**를 한 번의 연결 동작으로 이어갑니다.
- 현재 자동등록 형식은 같은 ID·비밀값을 유지합니다. 미등록이 확인된 구버전 수동 기기명·이전 비밀값은 새 자동등록 정보로 전환합니다. 사용자 로그인이나 토큰 입력은 없습니다.
- 후보 정보는 등록 요청 전에 암호화 저장하며, 타임아웃·앱 재시작에도 같은 후보로 재시도합니다. 문자 DB·동기화 위치를 유지하고 전송은 최종 연결 확인 후 재개합니다.
- 복구 전에 시작한 전송의 401이 늦게 도착해도, 연결 세대를 비교해 해당 문자를 재시도 상태로 유지합니다.
- `자동등록 확인` 단계가 로그에 남습니다. 일반 410, 401/403, 응답에 단어만 포함된 경우에는 정보를 교체하지 않습니다. 등록 실패나 두 번째 ping 실패는 추가 반복 없이 종료합니다.
- 함께 준비된 서버의 ping 미등록 응답 계약이 필요합니다. 해당 서버 변경이 운영에 반영되기 전에는 과거 401 응답에서 자동 복구하지 않습니다.
- 버전 `1.0.5`, `versionCode=6`, 기존 인증서 유지.

## 1.0.4 연결 실패 진단

- **환경설정 → 최근 연결 로그**에서 시각, 권한/등록/서버 확인/상태 전송 단계, 성공·실패, HTTP 상태 또는 DNS·시간 초과·보안 연결 오류를 확인하고 복사합니다.
- 최근 50건만 앱 내부에 보관하며 앱을 다시 실행해도 유지합니다. 토큰·기기 ID·전화번호·문자 내용·요청/응답 본문·예외 메시지는 기록하지 않습니다.
- HTTP 404는 서버 연결 기능의 업데이트 상태를 확인하도록 안내합니다. HTTP 200이어도 성공 확인이 없으면 실패로 기록합니다.
- 버전 `1.0.4`, `versionCode=5`. 기존 서명과 설치 데이터를 유지합니다. 설치 전 실패 기록은 소급 생성하지 않으므로 업데이트 후 연결을 다시 시도해야 합니다.

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

현재 앱은 수신 SMS·MMS와 지원 삼성 단말의 채팅을 관리자 화면으로 전달합니다. 금융사 판별은 서버에서 수행합니다.

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
**수신 → Room 선저장 → Node 모바일 API로 HTTPS POST → 실패 시 WorkManager 재전송**을 담당합니다. Node는 기존 Spring 저장·금융사 판별·OTP·사용자 매칭을 유지하고 영구 이벤트 저장까지 마친 뒤 성공 ACK를 반환합니다.

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

최신 1.0.10 설치 후에는 첫 화면에서 **연결**을 누르고 Android가 요청하는 SMS 권한을 허용하면 자동 연결됩니다. 환경설정에서는 최근 연결 로그와 권한·배터리·자동 실행을 확인합니다.

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

## 1.0.7 누락 복구와 전송

- 연결 성공·앱 시작·재부팅·앱 업데이트·네트워크 복구에서 같은 `PendingSmsWorker`를 예약합니다. 15분 heartbeat도 OS가 허용하는 시점에 복구를 예약합니다.
- 복구는 SMS Provider의 마지막 ID 이후만 최대 250행씩 Room에 저장하고, 전송은 50건씩 최대 5묶음/약 60초 한도까지 이어집니다. 실행 한도가 지나면 직렬 WorkManager 후속 작업으로 계속합니다.
- 외부 복구 요청과 후속 작업 모두 `APPEND_OR_REPLACE`로 보존합니다. 실행 종료 직전 연결 복구 요청이 발생해도 `KEEP`에 흡수되어 사라지지 않습니다.
- 네트워크·408·429·5xx 등 실제 전송 실패에 backoff를 적용합니다. 정상 잔량은 지연 없이 계속 처리합니다. 기존 계약대로 400은 해당 문자만 중단하고, 401/403은 연결 확인이 필요하며, 경로 미반영 404나 미등록 410 등 나머지 응답은 재시도 대상으로 보존합니다.
- 최초 ID 방식 전환 중에는 기존 시간/동의 경계와 snapshot을 암호화된 설정에 보존합니다. 페이지 저장 실패나 취소는 완료되지 않은 페이지의 체크포인트를 앞당기지 않습니다.
- 화면의 서버 연결 결과와 문자 동기화 상태를 분리합니다. 문자함 ID가 저장된 ID보다 작아진 경우 자동으로 이력을 재수집하지 않고 확인 필요 상태를 표시합니다.
- 연결 로그는 고정 단계·HTTP 코드·처리시간·건수·재시도 여부·UUIDv4 요청 ID만 포함합니다. 요청 ID는 Node의 `X-Request-ID` 로그와 대조할 수 있습니다. 문자 본문·발신번호·토큰·전송키는 로그에 넣지 않습니다.
- Android 12 이상에서만 expedited 작업을 사용하고, Android 8–11에서는 일반 작업으로 실행합니다. OS 절전·강제 종료 상태에서 즉시 전달을 보장하지 않으므로 화면 꺼짐/재부팅/망 복구를 실기기에서 검증해야 합니다.
- Room 스키마 버전은 1을 유지하며 파괴적 migration fallback은 제거했습니다. 설치 시 기존 앱을 삭제하거나 데이터를 초기화하지 마세요. 운영 Node와 관리자 반영 뒤 같은 서명의 1.0.7(versionCode 8)을 업데이트합니다.

## 1.0.8 새 SMS 자동 전달 보완

- `SMS_RECEIVED`가 이미 Room에 저장된 미전송 문자를 만나도 개별 전송 작업을 예약합니다. `SENT`와 영구 오류 상태는 유지합니다.
- 연결 성공은 과거 복구 작업의 재시도 대기를 해제합니다. 일반 앱 시작·네트워크·heartbeat 후속 작업은 계속 직렬로 보존합니다.
- 실행 중인 중계 서비스가 SMS Provider 변경 알림을 구독합니다. 300ms 동안 알림을 합쳐 마지막 처리 ID 이후만 50행씩 확인하고 개별 전송을 예약하며, 실행 중인 확인을 취소하거나 주기적으로 조회하지 않습니다.
- Provider의 유효 `date_sent`와 수신 방송의 발신시각으로 신규 `sms-v2:` 키를 맞춥니다. Provider `date`는 표시 시각으로 유지하고, 기존 저장 키·성공 기록을 먼저 재사용합니다. `date_sent`가 없으면 기존 수신 시각 방식을 사용하며, 동일 시각 키는 방송과 합칩니다. 발신시각을 제공하지 않고 두 경로의 시각까지 다르면 동일 문자인지 안전하게 확정할 수 없어 임의의 본문·시간창 매칭은 하지 않습니다.
- 복사 가능한 로그에 새 문자 수신·휴대폰 저장·전송 예약·문자함 변경 확인을 기록합니다. 번호·본문·기기 ID·토큰은 기록하지 않습니다.
- 버전 `1.0.8`, `versionCode=9`, 기존 서명과 Room 스키마 1을 유지합니다. 기존 앱 위에 업데이트하고 한 번 연결한 뒤 새 일반 SMS를 받아 확인하세요. 강제 종료된 앱은 다시 실행해야 합니다.

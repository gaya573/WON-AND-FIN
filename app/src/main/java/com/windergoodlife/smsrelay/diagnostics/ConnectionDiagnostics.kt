package com.windergoodlife.smsrelay.diagnostics

import com.windergoodlife.smsrelay.repository.RelayConnectionException
import com.windergoodlife.smsrelay.repository.ConnectionVerificationException
import java.io.IOException
import java.io.InterruptedIOException
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

enum class ConnectionLogStage(val label: String) {
    PERMISSION("SMS 권한"), PREPARING("연결 준비"), REGISTER("휴대폰 등록"), AUTO_REGISTER("자동등록 확인"),
    PING("서버 응답 확인"), HEARTBEAT("연결 상태 전송"), COMPLETE("연결 결과"),
    INBOX("누락 문자 확인"), UPLOAD("문자 전송"), SYNC("문자 동기화"),
    SMS_RECEIVE("SMS_RECEIVED 새 문자 수신"), LOCAL_STORE("휴대폰 저장"), UPLOAD_QUEUE("전송 예약"), SMS_PROVIDER("문자함 변경 확인"),
    SMS_CALLBACK("SmsReceiver.onReceive 진입"), SMS_PDU("SMS_RECEIVED PDU 배열"),
    SMS_DECODE("SMS_RECEIVED PDU 해석"), SMS_GROUPS("SMS_RECEIVED 발신자 그룹"), SMS_ASYNC("SMS_RECEIVED 비동기 처리"),
    MMS_PROVIDER("MMS 누락 확인"), CHAT_PROVIDER("삼성 채팅 누락 확인"), CHAT_FILE_PROVIDER("삼성 채팅 첨부 확인")
}

enum class ConnectionLogOutcome(val label: String) {
    WAITING("허용 대기"), STARTED("시작"), SUCCEEDED("성공"), FAILED("실패"), ALREADY_STORED("기존 저장 유지"), IGNORED("처리 대상 아님")
}

enum class ConnectionFailureReason(val label: String) {
    PERMISSION_DENIED("SMS 권한 거부"), SETTINGS_REQUIRED("휴대폰 설정에서 권한 허용 필요"),
    TIMEOUT("서버 응답 시간 초과"), DNS("서버 주소 조회 실패"), TLS("보안 연결 실패"),
    NETWORK("네트워크 연결 실패"), SERVER_RESPONSE("서버 응답 오류"),
    INVALID_ACK("서버 성공 확인 없음"), LOCAL_SETUP("휴대폰 연결 준비 실패"),
    CANCELLED("연결 작업 중단"), PROVIDER_RESET("문자함 변경으로 복구 확인 필요"),
    PROVIDER_UNAVAILABLE("문자함 조회 실패"), LOCAL_STORAGE("휴대폰 저장 실패"),
    AUTH_REJECTED("기기 연결 확인 필요"), PAYLOAD_REJECTED("전송 데이터 확인 필요"),
    SMS_DECODE("수신 문자 확인 실패"), WORK_SCHEDULING("전송 작업 예약 실패"), CONNECTION_REQUIRED("서버 연결 확인 대기"),
    SMS_ACTION_MISSING("action 없음"), SMS_ACTION_OTHER("다른 action"), SMS_PDU_UNAVAILABLE("PDU 배열 확인 불가"),
    SMS_ASYNC_UNAVAILABLE("goAsync 시작 실패"), SMS_PROCESSING("수신 문자 처리 실패")
}

/** Only allowlisted values enter diagnostics. Never serialize exception messages or request data. */
data class ConnectionDiagnostic(
    val stage: ConnectionLogStage,
    val outcome: ConnectionLogOutcome,
    val httpStatus: Int? = null,
    val reason: ConnectionFailureReason? = null,
    val requestId: String? = null,
    val elapsedMs: Long? = null,
    val count: Int? = null,
    val retryable: Boolean? = null
)

fun safeRequestId(value: String?): String? = value?.takeIf {
    it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))
}?.lowercase()

data class ConnectionLogEntry(val timestamp: Long, val event: ConnectionDiagnostic)

fun interface ConnectionLogSink {
    fun record(event: ConnectionDiagnostic)
}

fun connectionFailure(stage: ConnectionLogStage, failure: Throwable): ConnectionDiagnostic = ConnectionDiagnostic(
    stage, ConnectionLogOutcome.FAILED,
    (when (failure) {
        is RelayConnectionException -> failure.code
        is ConnectionVerificationException -> failure.httpStatus
        else -> null
    })?.takeIf { it in 100..599 },
    when (failure) {
        is RelayConnectionException -> ConnectionFailureReason.SERVER_RESPONSE
        is ConnectionVerificationException -> ConnectionFailureReason.INVALID_ACK
        is JsonDataException, is JsonEncodingException -> ConnectionFailureReason.INVALID_ACK
        is CancellationException -> ConnectionFailureReason.CANCELLED
        is SocketTimeoutException -> ConnectionFailureReason.TIMEOUT
        is UnknownHostException -> ConnectionFailureReason.DNS
        is SSLException -> ConnectionFailureReason.TLS
        is InterruptedIOException -> ConnectionFailureReason.TIMEOUT
        is ConnectException, is IOException -> ConnectionFailureReason.NETWORK
        else -> ConnectionFailureReason.LOCAL_SETUP
    }
)

/** Fixed-size typed buffer; malformed persisted rows are discarded rather than displayed. */
class ConnectionLogBuffer(saved: String = "") {
    private val records = ArrayDeque<ConnectionLogEntry>()

    init {
        saved.lineSequence().forEach { line ->
            val fields = line.split('|')
            if (fields.size == 5 || fields.size == 9) runCatching {
                val at = fields[0].toLong().also { require(it > 0) }
                val code = fields[3].takeIf { it.isNotEmpty() }?.toInt()?.also { require(it in 100..599) }
                append(ConnectionLogEntry(at, ConnectionDiagnostic(
                    ConnectionLogStage.valueOf(fields[1]), ConnectionLogOutcome.valueOf(fields[2]), code,
                    fields[4].takeIf { it.isNotEmpty() }?.let(ConnectionFailureReason::valueOf),
                    fields.getOrNull(5)?.takeIf { it.isNotEmpty() }?.let { requireNotNull(safeRequestId(it)) },
                    fields.getOrNull(6)?.takeIf { it.isNotEmpty() }?.toLong()?.also { require(it in 0..86_400_000L) },
                    fields.getOrNull(7)?.takeIf { it.isNotEmpty() }?.toInt()?.also { require(it >= 0) },
                    fields.getOrNull(8)?.takeIf { it.isNotEmpty() }?.toBooleanStrict())))
            }
        }
    }

    fun record(event: ConnectionDiagnostic, timestamp: Long) {
        append(ConnectionLogEntry(timestamp, event.copy(httpStatus = event.httpStatus?.takeIf { it in 100..599 },
            requestId = safeRequestId(event.requestId), elapsedMs = event.elapsedMs?.takeIf { it in 0..86_400_000L },
            count = event.count?.takeIf { it >= 0 })))
    }

    private fun append(entry: ConnectionLogEntry) {
        records.addLast(entry)
        while (records.size > 50) records.removeFirst()
    }

    fun snapshot(): List<ConnectionLogEntry> = records.toList()

    fun serialize(): String = records.joinToString("\n") {
        listOf(it.timestamp, it.event.stage.name, it.event.outcome.name, it.event.httpStatus ?: "", it.event.reason?.name ?: "",
            it.event.requestId ?: "", it.event.elapsedMs ?: "", it.event.count ?: "", it.event.retryable ?: "").joinToString("|")
    }
}

object ConnectionLogFormatter {
    fun format(entries: List<ConnectionLogEntry>): String {
        if (entries.isEmpty()) return "아직 연결 기록이 없습니다. 연결을 누르면 이곳에 기록됩니다."
        val format = DateTimeFormatter.ofPattern("MM.dd HH:mm:ss XXX").withZone(ZoneId.systemDefault())
        return entries.asReversed().joinToString("\n\n") { entry ->
            val event = entry.event
            buildList {
                add(format.format(Instant.ofEpochMilli(entry.timestamp)))
                add(event.stage.label)
                add(event.outcome.label)
                event.httpStatus?.let { add("HTTP $it") }
                event.reason?.let { add(it.label) }
                event.count?.let { add("${it}건") }
                event.elapsedMs?.let { add("${it}ms") }
                event.retryable?.let { add(if (it) "재시도 예정" else "자동 재시도 없음") }
                event.requestId?.let { add("요청 ID $it") }
            }.joinToString(" · ")
        }
    }
}

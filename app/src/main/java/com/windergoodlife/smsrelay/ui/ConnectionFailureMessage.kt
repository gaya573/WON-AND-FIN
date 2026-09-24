package com.windergoodlife.smsrelay.ui

import com.windergoodlife.smsrelay.repository.ConnectionVerificationException
import com.windergoodlife.smsrelay.repository.RelayConnectionException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun connectionFailureMessage(failure: Throwable): String = when {
    failure is RelayConnectionException && failure.code == 404 -> "서버에서 연결 기능을 찾지 못했습니다. 서버 업데이트 상태를 확인해 주세요. 환경설정의 최근 연결 로그에서 실패 단계를 확인할 수 있습니다."
    failure is RelayConnectionException && failure.code == 401 -> "저장된 연결 정보를 사용할 수 없습니다. 관리자에게 이 휴대폰의 연결 상태를 확인해 주세요"
    failure is RelayConnectionException && failure.code == 403 -> "사용 중지된 휴대폰입니다. 관리자에게 연결 상태를 확인해 주세요"
    failure is RelayConnectionException && failure.code == 429 -> "연결 요청이 많습니다. 잠시 후 다시 연결해 주세요"
    failure is RelayConnectionException && failure.code == 400 -> "휴대폰 정보를 확인하지 못했습니다. 앱을 다시 실행한 뒤 연결해 주세요"
    failure is RelayConnectionException -> "서버에 연결하지 못했습니다. 환경설정의 최근 연결 로그를 확인한 뒤 다시 연결해 주세요"
    failure is SocketTimeoutException || failure is InterruptedIOException -> "서버 응답이 늦어 연결하지 못했습니다. 인터넷 상태를 확인하고 잠시 후 다시 연결해 주세요"
    failure is UnknownHostException -> "서버 주소를 찾지 못했습니다. Wi-Fi 또는 모바일 데이터 연결을 확인해 주세요"
    failure is SSLException -> "서버와 보안 연결을 하지 못했습니다. 휴대폰 날짜·시간과 인터넷 연결을 확인해 주세요"
    failure is IOException -> "인터넷 연결을 확인한 뒤 다시 연결해 주세요"
    failure is ConnectionVerificationException -> "서버에서 연결 완료를 확인하지 못했습니다. 환경설정의 최근 연결 로그를 확인해 주세요"
    else -> "연결 준비를 완료하지 못했습니다. SMS 권한을 확인하고 다시 연결해 주세요. 자세한 내용은 환경설정의 최근 연결 로그에서 확인할 수 있습니다."
}

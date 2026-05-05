package com.openremit.api.infrastructure.idempotency

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

class CachedBodyHttpServletRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {

    val cachedBody: ByteArray = request.inputStream.readAllBytes()

    override fun getInputStream(): ServletInputStream = CachedBodyServletInputStream(cachedBody)

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(getInputStream(), characterEncoding ?: Charsets.UTF_8.name()))

    private class CachedBodyServletInputStream(body: ByteArray) : ServletInputStream() {
        private val stream = ByteArrayInputStream(body)
        override fun read(): Int = stream.read()
        override fun available(): Int = stream.available()
        override fun isFinished(): Boolean = stream.available() == 0
        override fun isReady(): Boolean = true
        override fun setReadListener(readListener: ReadListener?) {
            // not used
        }
    }
}

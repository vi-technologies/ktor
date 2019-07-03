/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.cio

import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.network.selector.*
import io.ktor.util.collections.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

internal class CIOEngine(
    override val config: CIOEngineConfig
) : CallScope("ktor-cio"), HttpClientEngine {
    override val dispatcher: CoroutineDispatcher by lazy { createClientDispatcher(config.threadsCount) }

    private val endpoints = ConcurrentMap<String, Endpoint>()

    @UseExperimental(InternalCoroutinesApi::class)
    private val selectorManager: SelectorManager by lazy { platformSelectorManager(dispatcher) }

    private val connectionFactory = ConnectionFactory(selectorManager, config.maxConnectionsCount)
    private val closed = atomic(false)

    private val proxy = when (val type = config.proxy?.type()) {
        Proxy.Type.DIRECT,
        null -> null
        Proxy.Type.HTTP -> config.proxy
        else -> throw IllegalStateException("Proxy of type $type is unsupported by CIO engine.")
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        while (true) {
            if (closed.value) throw ClientClosedException()

            val endpoint = selectEndpoint(data.url, proxy)
            val callContext = createCallContext()
            try {
                return endpoint.execute(data, callContext)
            } catch (cause: ClosedSendChannelException) {
                if (closed.value) throw ClientClosedException(cause)
                (callContext[Job] as? CompletableJob)?.completeExceptionally(cause)
                continue
            } catch (cause: Throwable) {
                (callContext[Job] as? CompletableJob)?.completeExceptionally(cause)
                throw cause
            } finally {
                if (closed.value) endpoint.close()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) throw ClientClosedException()

        endpoints.forEach { (_, endpoint) ->
            endpoint.close()
        }

        coroutineContext[Job]?.invokeOnCompletion {
            selectorManager.close()
        }

        super.close()
    }

    private fun selectEndpoint(url: Url, proxy: ProxyConfig?): Endpoint {
        val host: String
        val port: Int
        val protocol: URLProtocol = url.protocol

        if (proxy != null) {
            val proxyAddress = proxy.address() as InetSocketAddress
            host = proxyAddress.hostName
            port = proxyAddress.port
        } else {
            host = url.host
            port = url.port
        }

        val endpointId = "$host:$port:$protocol"

        return endpoints.getOrDefault(endpointId) {
            val secure = (protocol.isSecure())
            Endpoint(
                host, port, proxy != null, secure,
                config,
                connectionFactory, coroutineContext,
                onDone = { endpoints.remove(endpointId) }
            )
        }
    }
}

@Suppress("KDocMissingDocumentation")
class ClientClosedException(override val cause: Throwable? = null) : IllegalStateException("Client already closed")

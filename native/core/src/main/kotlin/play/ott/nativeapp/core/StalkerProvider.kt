package play.ott.nativeapp.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import play.ott.core.*

/** HTTP, URL/JSON codecs, locking and Android model conversion for the common protocols. */
internal class StalkerProvider(private val http: ProviderHttp) {
    private val sessions=StalkerTokens<SourceConfig>()
    private val sessionMutex=Mutex()

    suspend fun load(config: SourceConfig): Catalog = translate {
        val rpc=isRpc(config)
        val endpoint=if(rpc)config.url else endpoint(config).toString()
        val load=StalkerNativeLoad(rpc,config.id,{resolveHttp(endpoint,it)}, {id->rpcStream(config,id)}, {stableId(*it.toTypedArray())})
        while(true) {
            val request=load.request ?: break
            load.accept(if(rpc)rpc(config,request.action) else call(config,request))
        }
        Catalog(load.result().map { item(it,config) },listOfNotNull(config.epgUrl.takeIf(String::isNotBlank)?.let {httpUrl(it).toString()}))
    }
    suspend fun resolve(config: SourceConfig, entry: MediaEntry): PlaybackStream = translate {
        val url=if(isRpc(config))httpUrl(entry.url).toString() else {
            val data=call(config,StalkerProtocol.linkRequest(entry.url,format=StalkerFormat.NATIVE))
            StalkerProtocol.link(data,StalkerFormat.NATIVE,{resolveHttp(endpoint(config).toString(),it)},{httpUrl(it).host})
        }
        PlaybackStream(url,mergedHeaders(config.headers,entry.headers),inferMimeType(url))
    }
    private fun endpoint(config: SourceConfig): HttpUrl {
        val input=httpUrl(config.url)
        if(StalkerProtocol.nativeExplicitEndpoint(input.pathSegments))return input.newBuilder().query(null).fragment(null).build()
        return input.newBuilder().encodedPath("/").query(null).fragment(null).apply {
            StalkerProtocol.nativeEndpoint(input.pathSegments).forEach {addPathSegment(it)}
        }.build()
    }
    private fun isRpc(config: SourceConfig)=StalkerProtocol.isRpc(httpUrl(config.url).encodedPath)
    private fun rpcStream(config: SourceConfig,id: String): String {
        val input=httpUrl(config.url)
        val base=input.newBuilder().encodedPath("/").query(null).fragment(null).apply {
            StalkerProtocol.rpcBaseSegments(input.pathSegments).forEach {addPathSegment(it)}
        }.build()
        return StalkerProtocol.rpcStream(id,config.mac) {path,query->base.newBuilder().apply {
            path.forEach {addPathSegment(it)};query.forEach {(key,value)->addQueryParameter(key,value)}
        }.build().toString()}
    }
    private suspend fun token(config: SourceConfig): String=sessionMutex.withLock {
        sessions.get(config)?.let {return@withLock it}
        val data=raw(config,StalkerProtocol.handshake(),null)
        val token=StalkerProtocol.token(data,StalkerFormat.NATIVE)
        sessions.put(config,token);token
    }
    private suspend fun call(config: SourceConfig, request: StalkerRequest): ProviderValue {
        var token=token(config)
        val retry=StalkerRetry()
        while(true) {
            try {return raw(config,request,token)}catch(error:ProviderException) {
                if(!retry.reject(error.statusCode))throw error
                sessionMutex.withLock {sessions.invalidate(config,token)}
                token=token(config)
            }
        }
    }
    private suspend fun raw(config: SourceConfig, request: StalkerRequest, token: String?): ProviderValue {
        val endpoint=endpoint(config)
        val url=endpoint.newBuilder().apply {StalkerProtocol.query(request,StalkerFormat.NATIVE).forEach {(key,value)->addQueryParameter(key,value)}}.build()
        val headers=mergedHeaders(config.headers,StalkerProtocol.headers(config.mac,"","",ProviderValue.missing,token,StalkerLocation(endpoint.toString(),""),StalkerFormat.NATIVE,{it}))
        return StalkerProtocol.unwrap(parseProviderJson(http.get(url.toString(),headers).text()).toProviderValue(),StalkerFormat.NATIVE)
    }
    private suspend fun rpc(config: SourceConfig, method: String): ProviderValue {
        val body=StalkerProtocol.rpc(method,config.mac).toJsonElement().toString()
        return StalkerProtocol.unwrap(parseProviderJson(http.postJson(config.url,body,config.headers).text()).toProviderValue(),StalkerFormat.RPC)
    }
    private fun item(value: StalkerItem,config: SourceConfig)=MediaEntry(value.id,config.id,value.name,value.url,group=value.group,logo=value.logo,
        epgId=value.epgId,headers=config.headers,providerId=value.providerId,headerOrigins=headerOrigins(config.headers,config.url),
        nameMessage=if(value.generatedName)CoreMessage(CoreMessageKey.CHANNEL,listOf(value.providerId)) else null,
        groupMessage=if(value.generatedGroup)CoreMessage(CoreMessageKey.OTHER_GROUP) else null)
    private suspend fun <T> translate(block: suspend ()->T): T=try {block()}catch(error:StalkerFailure) {
        throw ProviderException(when(error.code) {
            "NATIVE_FORMAT"->"Invalid Stalker response"
            "NATIVE_RESULT"->"Stalker response has no result"
            "NATIVE_EMPTY"->"Stalker portal returned no result"
            "NATIVE_REJECTED"->"Stalker portal rejected the request"
            "NATIVE_HANDSHAKE"->"Stalker handshake did not return an access token"
            "NATIVE_GENRES"->"Stalker genres has an unexpected format"
            "NATIVE_PAGE"->"Invalid Stalker channel page"
            "NATIVE_COUNT"->"Portal contains more than 100,000 channels"
            "NATIVE_EARLY"->"Portal channel pagination ended before all channels arrived"
            "NATIVE_REPEAT"->"Portal repeated a channel page; refusing an incomplete catalogue"
            "NATIVE_PAGE_LIMIT"->"Portal exceeds the channel pagination limit"
            "NATIVE_LINK"->"Portal did not return a playable link"
            "LOCAL_LINK"->"Portal returned an unresolved local playback command"
            "RPC_FORMAT"->"Invalid Stalker JSON-RPC response"
            "RPC_ERROR"->"Stalker JSON-RPC request was rejected"
            "RPC_EMPTY"->"Stalker JSON-RPC returned no result"
            "RPC_REJECTED"->"Stalker JSON-RPC rejected the request"
            "RPC_CATALOG"->"Invalid JSON-RPC channel catalogue"
            else->"Provider returned an unsupported response"
        })
    }
}

package proto

import io.grpc.CallOptions
import io.grpc.CallOptions.DEFAULT
import io.grpc.Channel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.ServerServiceDefinition.builder
import io.grpc.ServiceDescriptor
import io.grpc.Status.UNIMPLEMENTED
import io.grpc.StatusException
import io.grpc.kotlin.AbstractCoroutineServerImpl
import io.grpc.kotlin.AbstractCoroutineStub
import io.grpc.kotlin.ClientCalls.bidiStreamingRpc
import io.grpc.kotlin.ClientCalls.unaryRpc
import io.grpc.kotlin.ServerCalls.bidiStreamingServerMethodDefinition
import io.grpc.kotlin.ServerCalls.unaryServerMethodDefinition
import io.grpc.kotlin.StubFor
import kotlin.String
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.flow.Flow
import proto.NezhaServiceGrpc.getServiceDescriptor

/**
 * Holder for Kotlin coroutine-based client and server APIs for proto.NezhaService.
 */
public object NezhaServiceGrpcKt {
  public const val SERVICE_NAME: String = NezhaServiceGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val reportSystemStateMethod: MethodDescriptor<Nezha.State, Nezha.Receipt>
    @JvmStatic
    get() = NezhaServiceGrpc.getReportSystemStateMethod()

  public val reportSystemInfoMethod: MethodDescriptor<Nezha.Host, Nezha.Receipt>
    @JvmStatic
    get() = NezhaServiceGrpc.getReportSystemInfoMethod()

  public val requestTaskMethod: MethodDescriptor<Nezha.TaskResult, Nezha.Task>
    @JvmStatic
    get() = NezhaServiceGrpc.getRequestTaskMethod()

  public val iOStreamMethod: MethodDescriptor<Nezha.IOStreamData, Nezha.IOStreamData>
    @JvmStatic
    get() = NezhaServiceGrpc.getIOStreamMethod()

  public val reportGeoIPMethod: MethodDescriptor<Nezha.GeoIP, Nezha.GeoIP>
    @JvmStatic
    get() = NezhaServiceGrpc.getReportGeoIPMethod()

  public val reportSystemInfo2Method: MethodDescriptor<Nezha.Host, Nezha.Uint64Receipt>
    @JvmStatic
    get() = NezhaServiceGrpc.getReportSystemInfo2Method()

  /**
   * A stub for issuing RPCs to a(n) proto.NezhaService service as suspending coroutines.
   */
  @StubFor(NezhaServiceGrpc::class)
  public class NezhaServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<NezhaServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): NezhaServiceCoroutineStub =
        NezhaServiceCoroutineStub(channel, callOptions)

    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * The [Flow] of requests is collected once each time the [Flow] of responses is
     * collected. If collection of the [Flow] of responses completes normally or
     * exceptionally before collection of `requests` completes, the collection of
     * `requests` is cancelled.  If the collection of `requests` completes
     * exceptionally for any other reason, then the collection of the [Flow] of responses
     * completes exceptionally for the same reason and the RPC is cancelled with that reason.
     *
     * @param requests A [Flow] of request messages.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    public fun reportSystemState(requests: Flow<Nezha.State>, headers: Metadata = Metadata()):
        Flow<Nezha.Receipt> = bidiStreamingRpc(
      channel,
      NezhaServiceGrpc.getReportSystemStateMethod(),
      requests,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun reportSystemInfo(request: Nezha.Host, headers: Metadata = Metadata()):
        Nezha.Receipt = unaryRpc(
      channel,
      NezhaServiceGrpc.getReportSystemInfoMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * The [Flow] of requests is collected once each time the [Flow] of responses is
     * collected. If collection of the [Flow] of responses completes normally or
     * exceptionally before collection of `requests` completes, the collection of
     * `requests` is cancelled.  If the collection of `requests` completes
     * exceptionally for any other reason, then the collection of the [Flow] of responses
     * completes exceptionally for the same reason and the RPC is cancelled with that reason.
     *
     * @param requests A [Flow] of request messages.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    public fun requestTask(requests: Flow<Nezha.TaskResult>, headers: Metadata = Metadata()):
        Flow<Nezha.Task> = bidiStreamingRpc(
      channel,
      NezhaServiceGrpc.getRequestTaskMethod(),
      requests,
      callOptions,
      headers
    )

    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * The [Flow] of requests is collected once each time the [Flow] of responses is
     * collected. If collection of the [Flow] of responses completes normally or
     * exceptionally before collection of `requests` completes, the collection of
     * `requests` is cancelled.  If the collection of `requests` completes
     * exceptionally for any other reason, then the collection of the [Flow] of responses
     * completes exceptionally for the same reason and the RPC is cancelled with that reason.
     *
     * @param requests A [Flow] of request messages.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    public fun iOStream(requests: Flow<Nezha.IOStreamData>, headers: Metadata = Metadata()):
        Flow<Nezha.IOStreamData> = bidiStreamingRpc(
      channel,
      NezhaServiceGrpc.getIOStreamMethod(),
      requests,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun reportGeoIP(request: Nezha.GeoIP, headers: Metadata = Metadata()):
        Nezha.GeoIP = unaryRpc(
      channel,
      NezhaServiceGrpc.getReportGeoIPMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun reportSystemInfo2(request: Nezha.Host, headers: Metadata = Metadata()):
        Nezha.Uint64Receipt = unaryRpc(
      channel,
      NezhaServiceGrpc.getReportSystemInfo2Method(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the proto.NezhaService service based on Kotlin coroutines.
   */
  public abstract class NezhaServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns a [Flow] of responses to an RPC for proto.NezhaService.ReportSystemState.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status
     * `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param requests A [Flow] of requests from the client.  This flow can be
     *        collected only once and throws [java.lang.IllegalStateException] on attempts to
     * collect
     *        it more than once.
     */
    public open fun reportSystemState(requests: Flow<Nezha.State>): Flow<Nezha.Receipt> = throw
        StatusException(UNIMPLEMENTED.withDescription("Method proto.NezhaService.ReportSystemState is unimplemented"))

    /**
     * Returns the response to an RPC for proto.NezhaService.ReportSystemInfo.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun reportSystemInfo(request: Nezha.Host): Nezha.Receipt = throw
        StatusException(UNIMPLEMENTED.withDescription("Method proto.NezhaService.ReportSystemInfo is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for proto.NezhaService.RequestTask.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status
     * `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param requests A [Flow] of requests from the client.  This flow can be
     *        collected only once and throws [java.lang.IllegalStateException] on attempts to
     * collect
     *        it more than once.
     */
    public open fun requestTask(requests: Flow<Nezha.TaskResult>): Flow<Nezha.Task> = throw
        StatusException(UNIMPLEMENTED.withDescription("Method proto.NezhaService.RequestTask is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for proto.NezhaService.IOStream.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status
     * `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param requests A [Flow] of requests from the client.  This flow can be
     *        collected only once and throws [java.lang.IllegalStateException] on attempts to
     * collect
     *        it more than once.
     */
    public open fun iOStream(requests: Flow<Nezha.IOStreamData>): Flow<Nezha.IOStreamData> = throw
        StatusException(UNIMPLEMENTED.withDescription("Method proto.NezhaService.IOStream is unimplemented"))

    /**
     * Returns the response to an RPC for proto.NezhaService.ReportGeoIP.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun reportGeoIP(request: Nezha.GeoIP): Nezha.GeoIP = throw
        StatusException(UNIMPLEMENTED.withDescription("Method proto.NezhaService.ReportGeoIP is unimplemented"))

    /**
     * Returns the response to an RPC for proto.NezhaService.ReportSystemInfo2.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun reportSystemInfo2(request: Nezha.Host): Nezha.Uint64Receipt = throw
        StatusException(UNIMPLEMENTED.withDescription("Method proto.NezhaService.ReportSystemInfo2 is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(bidiStreamingServerMethodDefinition(
      context = this.context,
      descriptor = NezhaServiceGrpc.getReportSystemStateMethod(),
      implementation = ::reportSystemState
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = NezhaServiceGrpc.getReportSystemInfoMethod(),
      implementation = ::reportSystemInfo
    ))
      .addMethod(bidiStreamingServerMethodDefinition(
      context = this.context,
      descriptor = NezhaServiceGrpc.getRequestTaskMethod(),
      implementation = ::requestTask
    ))
      .addMethod(bidiStreamingServerMethodDefinition(
      context = this.context,
      descriptor = NezhaServiceGrpc.getIOStreamMethod(),
      implementation = ::iOStream
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = NezhaServiceGrpc.getReportGeoIPMethod(),
      implementation = ::reportGeoIP
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = NezhaServiceGrpc.getReportSystemInfo2Method(),
      implementation = ::reportSystemInfo2
    )).build()
  }
}

package org.glassfish.jersey.undertow.connector;

import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.UndertowClient;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;
import jersey.repackaged.com.google.common.util.concurrent.SettableFuture;
import org.glassfish.jersey.client.ClientRequest;
import org.glassfish.jersey.client.ClientResponse;
import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.internal.Version;
import org.glassfish.jersey.message.internal.HeaderUtils;
import org.glassfish.jersey.message.internal.Statuses;
import org.xnio.*;
import org.xnio.streams.ChannelInputStream;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;


class UndertowConnector implements Connector {

    private Xnio xnio;
    private XnioWorker worker;
    private int ioThreads;
    private int workerThreads;
    private OptionMap workerOptions;
    private boolean directBuffers = true;
    private int bufferSize = 1024 * 16;
    private int buffersPerRegion = 20;
    private Pool<ByteBuffer> buffers;

    private final UndertowClient undertowClient = UndertowClient.getInstance();

    private final Client client;
    private final Configuration configuration;


    public UndertowConnector(Client client, Configuration runtimeConfig) {
        this.client = client;
        this.configuration = runtimeConfig;
        start();
    }

    public synchronized void start() {
        xnio = Xnio.getInstance(UndertowConnectorProvider.class.getClassLoader());
        try {
            worker = xnio.createWorker(OptionMap.builder()
                    .set(Options.WORKER_IO_THREADS, ioThreads)
                    .set(Options.CONNECTION_HIGH_WATER, 1000000)
                    .set(Options.CONNECTION_LOW_WATER, 1000000)
                    .set(Options.WORKER_TASK_CORE_THREADS, workerThreads)
                    .set(Options.WORKER_TASK_MAX_THREADS, workerThreads)
                    .set(Options.TCP_NODELAY, true)
                    .set(Options.CORK, true)
                    .addAll(workerOptions)
                    .getMap());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        buffers = new ByteBufferSlicePool(directBuffers ? BufferAllocator.DIRECT_BYTE_BUFFER_ALLOCATOR : BufferAllocator.BYTE_BUFFER_ALLOCATOR, bufferSize, bufferSize * buffersPerRegion);
    }

    @Override
    public ClientResponse apply(ClientRequest request) {
        Future<ClientResponse> resp = (Future<ClientResponse>) apply(request, new AsyncConnectorCallback() {
            @Override
            public void response(ClientResponse response) {
            }

            @Override
            public void failure(Throwable failure) {
            }
        });
        try {
            return resp.get();
        } catch (InterruptedException e) {
            throw new ProcessingException(e);
        } catch (ExecutionException e) {
            throw new ProcessingException(e.getCause());
        }
    }


    @Override
    public Future<?> apply(final ClientRequest request, final AsyncConnectorCallback callback) {
        final SettableFuture<ClientResponse> responseFuture = SettableFuture.create();

        undertowClient.connect(new ClientCallback<ClientConnection>() {
            @Override
            public void completed(final ClientConnection undertowConnection) {
                undertowConnection.sendRequest(toUndertowRequest(request), new ClientCallback<ClientExchange>() {
                    @Override
                    public void completed(ClientExchange clientExchange) {
                        io.undertow.client.ClientResponse undertowResponse = clientExchange.getResponse();
                        final ClientResponse jerseyResponse = new ClientResponse(
                                Statuses.from(undertowResponse.getResponseCode(),
                                        undertowResponse.getStatus()),
                                request);
                        MultivaluedMap<String, String> jerseyHeaders = jerseyResponse.getHeaders();
                        for (HeaderValues headerValues : undertowResponse.getResponseHeaders()) {
                            String headerName = headerValues.getHeaderName().toString();
                            List<String> values = jerseyHeaders.get(headerName);
                            if (values == null) {
                                values = new ArrayList<String>();
                                jerseyHeaders.put(headerName, values);
                            }
                            values.addAll(headerValues);
                        }
                        jerseyResponse.setEntityStream(new ChannelInputStream(clientExchange.getResponseChannel()));

                        callback.response(jerseyResponse);
                        responseFuture.set(jerseyResponse);
                    }

                    @Override
                    public void failed(IOException e) {
                        callback.failure(e);
                        responseFuture.setException(e);
                    }
                });
            }

            @Override
            public void failed(IOException e) {
                callback.failure(e);
                responseFuture.setException(e);
            }
        }, request.getUri(), worker.getIoThread(), buffers, OptionMap.EMPTY/*TODO*/);


        return responseFuture;
    }

    private io.undertow.client.ClientRequest toUndertowRequest(ClientRequest jerseyRequest) {
        final io.undertow.client.ClientRequest request = new io.undertow.client.ClientRequest();
        request.setPath(jerseyRequest.getUri().toString())
                .setMethod(HttpString.tryFromString(jerseyRequest.getMethod()));

        MultivaluedMap<String, String> stringHeaders = HeaderUtils.asStringHeaders(jerseyRequest.getHeaders());
        for (Map.Entry<String, List<String>> entry : stringHeaders.entrySet()) {
            HeaderValues values = request.getRequestHeaders().get(entry.getKey());
            if (values == null) {
                request.getRequestHeaders().putAll(HttpString.tryFromString(entry.getKey()), entry.getValue());
            }else{
                values.addAll(entry.getValue());
            }

        }

        return request;
    }


    @Override
    public void close() {

    }

    @Override
    public String getName() {
        return String.format("Async HTTP Undertow Connector %s", Version.getVersion());
    }
}

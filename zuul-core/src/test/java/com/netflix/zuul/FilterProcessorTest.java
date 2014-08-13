/**
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.zuul;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.reactivex.netty.protocol.http.client.HttpClientResponse;
import io.reactivex.netty.protocol.http.server.HttpResponseHeaders;
import io.reactivex.netty.protocol.http.server.HttpServerRequest;
import io.reactivex.netty.protocol.http.server.HttpServerResponse;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.subjects.PublishSubject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

public class FilterProcessorTest {
    @Mock FilterStore mockFilterStore;
    @Mock FiltersForRoute mockFilters;
    @Mock HttpServerResponse<ByteBuf> mockRxNettyResp;
    @Mock HttpResponseHeaders mockRespHeaders;
    private FilterProcessor processor;

    private final HttpRequest nettyReq = new HttpRequest() {
        @Override
        public HttpMethod getMethod() {
            return HttpMethod.GET;
        }

        @Override
        public HttpRequest setMethod(HttpMethod method) {
            return this;
        }

        @Override
        public String getUri() {
            return "/foo/test?qp=1";
        }

        @Override
        public HttpRequest setUri(String uri) {
            return this;
        }

        @Override
        public HttpRequest setProtocolVersion(HttpVersion version) {
            return this;
        }

        @Override
        public HttpVersion getProtocolVersion() {
            return HttpVersion.HTTP_1_1;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.EMPTY_HEADERS;
        }

        @Override
        public DecoderResult getDecoderResult() {
            return DecoderResult.SUCCESS;
        }

        @Override
        public void setDecoderResult(DecoderResult result) {

        }
    };
    private final HttpServerRequest<ByteBuf> rxNettyReq = new HttpServerRequest<>(nettyReq, PublishSubject.create());
    private final IngressRequest ingressReq = IngressRequest.from(rxNettyReq);

    private final HttpResponse nettyResp = new HttpResponse() {
        @Override
        public HttpResponseStatus getStatus() {
            return HttpResponseStatus.OK;
        }

        @Override
        public HttpResponse setStatus(HttpResponseStatus status) {
            return this;
        }

        @Override
        public HttpResponse setProtocolVersion(HttpVersion version) {
            return this;
        }

        @Override
        public HttpVersion getProtocolVersion() {
            return HttpVersion.HTTP_1_1;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.EMPTY_HEADERS;
        }

        @Override
        public DecoderResult getDecoderResult() {
            return DecoderResult.SUCCESS;
        }

        @Override
        public void setDecoderResult(DecoderResult result) {

        }
    };
    private final HttpClientResponse<ByteBuf> rxNettyResp = new HttpClientResponse<>(nettyResp, PublishSubject.create());
    private final IngressResponse ingressResp = IngressResponse.from(rxNettyResp);

    private final PreFilter successPreFilter = createPreFilter(1, true, (ingressReq, egressReq) -> {
        egressReq.addHeader("PRE", "DONE");
        return Observable.just(egressReq);
    });

    private final PreFilter shouldNotPreFilter = createPreFilter(2, false, (ingressReq, egressReq) -> {
        egressReq.addHeader("PRE", "SHOULDNOT");
        return Observable.just(egressReq);
    });

    private final RouteFilter successRouteFilter = createRouteFilter(1, true, egressReq -> Observable.just(ingressResp));

    private final PostFilter successPostFilter = createPostFilter(1, true, (ingressResp, egressResp) -> {
        egressResp.addHeader("POST", "DONE");
        return Observable.just(egressResp);
    });

    private final PostFilter shouldNotPostFilter = createPostFilter(2, false, (ingressResp, egressResp) -> {
        egressResp.addHeader("POST", "SHOULDNOT");
        return Observable.just(egressResp);
    });

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
        processor = new FilterProcessor(mockFilterStore);

        when(mockFilterStore.getFilters(ingressReq)).thenReturn(mockFilters);
        when(mockRxNettyResp.getHeaders()).thenReturn(mockRespHeaders);
    }

    private final CountDownLatch latch = new CountDownLatch(1);

    private final Action1<EgressResponse> onNextAssert = (egressResp) -> {
        System.out.println("onNext : " + egressResp);
        verify(mockRxNettyResp, times(1)).setStatus(HttpResponseStatus.OK);
    };
    private final Action1<Throwable> onErrorFail = (ex) -> { System.out.println("onError : " + ex); ex.printStackTrace(); fail(ex.getMessage());};
    private final Action0 onCompletedUnlatch = () -> { System.out.println("onCompleted"); latch.countDown();};

    private PreFilter createPreFilter(final int order, final boolean shouldFilter, Func2<IngressRequest, EgressRequest, Observable<EgressRequest>> behavior) {
        return new PreFilter() {
            @Override
            public Observable<EgressRequest> apply(IngressRequest ingressReq, EgressRequest egressReq) {
                System.out.println("Executing preFilter : " + this);
                return behavior.call(ingressReq, egressReq);
            }

            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public Observable<Boolean> shouldFilter(IngressRequest ingressReq) {
                return Observable.just(shouldFilter);
            }
        };
    }

    private RouteFilter createRouteFilter(final int order, final boolean shouldFilter, Func1<EgressRequest, Observable<IngressResponse>> behavior) {
        return new RouteFilter() {
            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public Observable<Boolean> shouldFilter(EgressRequest ingressReq) {
                return Observable.just(shouldFilter);
            }

            @Override
            public Observable<IngressResponse> apply(EgressRequest input) {
                System.out.println("Executing routeFilter : " + this);
                return behavior.call(input);
            }
        };
    }

    private PostFilter createPostFilter(final int order, final boolean shouldFilter, Func2<IngressResponse, EgressResponse, Observable<EgressResponse>> behavior) {
        return new PostFilter() {
            @Override
            public Observable<EgressResponse> apply(IngressResponse ingressResp, EgressResponse egressResp) {
                System.out.println("Executing postFilter : " + this);
                return behavior.call(ingressResp, egressResp);
            }

            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public Observable<Boolean> shouldFilter(IngressResponse ingressReq) {
                return Observable.just(shouldFilter);
            }
        };
    }

    @Test(timeout=1000)
    public void testApplyOneRoute() throws InterruptedException {
        when(mockFilters.getPreFilters()).thenReturn(new ArrayList<>());
        when(mockFilters.getRouteFilter()).thenReturn(successRouteFilter);
        when(mockFilters.getPostFilters()).thenReturn(new ArrayList<>());

        Observable<EgressResponse> result = processor.applyAllFilters(ingressReq, mockRxNettyResp);
        result.subscribe(onNextAssert, onErrorFail, onCompletedUnlatch);

        latch.await();
    }

    @Test(timeout=1000)
    public void testApplyOnePreOneRouteOnePost() throws InterruptedException {
        when(mockFilters.getPreFilters()).thenReturn(Arrays.asList(successPreFilter));
        when(mockFilters.getRouteFilter()).thenReturn(successRouteFilter);
        when(mockFilters.getPostFilters()).thenReturn(Arrays.asList(successPostFilter));

        Observable<EgressResponse> result = processor.applyAllFilters(ingressReq, mockRxNettyResp);
        result.subscribe(onNextAssert, onErrorFail, onCompletedUnlatch);

        latch.await();
        verify(mockRespHeaders, times(1)).addHeader("POST", "DONE");
    }

    @Test(timeout=1000)
    public void testApplyTwoPreOneRouteTwoPost() throws InterruptedException {
        when(mockFilters.getPreFilters()).thenReturn(Arrays.asList(successPreFilter, successPreFilter));
        when(mockFilters.getRouteFilter()).thenReturn(successRouteFilter);
        when(mockFilters.getPostFilters()).thenReturn(Arrays.asList(successPostFilter, successPostFilter));

        Observable<EgressResponse> result = processor.applyAllFilters(ingressReq, mockRxNettyResp);
        result.subscribe(onNextAssert, onErrorFail, onCompletedUnlatch);

        latch.await();
        verify(mockRespHeaders, times(2)).addHeader("POST", "DONE");
    }

    @Test(timeout=1000)
    public void testShouldFilterWorks() throws InterruptedException {
        when(mockFilters.getPreFilters()).thenReturn(Arrays.asList(successPreFilter, shouldNotPreFilter));
        when(mockFilters.getRouteFilter()).thenReturn(successRouteFilter);
        when(mockFilters.getPostFilters()).thenReturn(Arrays.asList(successPostFilter, shouldNotPostFilter));

        Observable<EgressResponse> result = processor.applyAllFilters(ingressReq, mockRxNettyResp);
        result.subscribe(onNextAssert, onErrorFail, onCompletedUnlatch);

        latch.await();
        verify(mockRespHeaders, times(1)).addHeader(eq("POST"), anyString());
    }

//    @Test
//    public void testErrorInPreFilter() {
//        assertEquals(0, 9);
//    }
//
//    @Test
//    public void testErrorInPreAndPostFilters() {
//        assertEquals(0, 9);
//    }
//
//    @Test
//    public void testErrorInRouteFilter() {
//        assertEquals(0, 9);
//    }
//
//    @Test
//    public void testErrorInRouteAndPostFilters() {
//        assertEquals(0, 9);
//    }
//
//    @Test
//    public void testErrorInPostFilter() {
//        assertEquals(0, 9);
//    }
}

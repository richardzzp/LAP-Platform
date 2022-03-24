package org.jetlinks.community.elastic.search.service.reactive;

import com.alibaba.fastjson.JSON;
import io.netty.util.internal.ObjectPool;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.hswebframework.ezorm.core.param.QueryParam;
import org.hswebframework.utils.time.DateFormatter;
import org.hswebframework.utils.time.DefaultDateFormatter;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.bean.FastBeanCopier;
import org.jetlinks.community.elastic.search.index.ElasticSearchIndexManager;
import org.jetlinks.community.elastic.search.index.ElasticSearchIndexMetadata;
import org.jetlinks.community.elastic.search.service.ElasticSearchService;
import org.jetlinks.community.elastic.search.utils.ElasticSearchConverter;
import org.jetlinks.community.elastic.search.utils.QueryParamTranslator;
import org.jetlinks.community.utils.SystemUtils;
import org.jetlinks.core.utils.FluxUtils;
import org.reactivestreams.Publisher;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 响应式ES数据库操作类
 *
 * @author zhouhao
 * @since 1.0
 **/
@Service("elasticSearchService")
@Slf4j
@DependsOn("reactiveElasticsearchClient")
@ConfigurationProperties(prefix = "elasticsearch")
public class ReactiveElasticSearchService implements ElasticSearchService {

    @Getter
    private final ReactiveElasticsearchClient restClient;
    @Getter
    private final ElasticSearchIndexManager indexManager;

    private FluxSink<Buffer> sink;

    public static final IndicesOptions indexOptions = IndicesOptions.fromOptions(
        true, true, false, false
    );

    static {
        DateFormatter.supportFormatter.add(new DefaultDateFormatter(Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}.+"), "yyyy-MM-dd'T'HH:mm:ss.SSSZ"));
    }

    public ReactiveElasticSearchService(ReactiveElasticsearchClient restClient,
                                        ElasticSearchIndexManager indexManager) {
        this.restClient = restClient;
        this.indexManager = indexManager;
        init();
    }

    @Override
    public <T> Flux<T> multiQuery(String[] index, Collection<QueryParam> queryParam, Function<Map<String, Object>, T> mapper) {
        return indexManager
            .getIndexesMetadata(index)
            .flatMap(idx -> Mono.zip(
                Mono.just(idx), getIndexForSearch(idx.getIndex())
            ))
            .take(1)
            .singleOrEmpty()
            .flatMapMany(indexMetadata -> {
                MultiSearchRequest request = new MultiSearchRequest();
                return Flux
                    .fromIterable(queryParam)
                    .flatMap(entry -> createSearchRequest(entry, index))
                    .doOnNext(request::add)
                    .then(Mono.just(request))
                    .flatMapMany(searchRequest -> restClient
                        .multiSearch(searchRequest)
                        .flatMapMany(response -> Flux.fromArray(response.getResponses()))
                        .flatMap(item -> {
                            if (item.isFailure()) {
                                log.warn(item.getFailureMessage(), item.getFailure());
                                return Mono.empty();
                            }
                            return Flux
                                .fromIterable(translate((map) -> mapper
                                    .apply(indexMetadata.getT1().convertFromElastic(map)), item.getResponse()));
                        }))
                    ;
            });
    }

    public <T> Flux<T> query(String index, QueryParam queryParam, Function<Map<String, Object>, T> mapper) {
        return this.query(new String[]{index}, queryParam, mapper);
    }

    public <T> Flux<T> query(String[] index, QueryParam queryParam, Function<Map<String, Object>, T> mapper) {
        if (queryParam.isPaging()) {
            return this
                .doQuery(index, queryParam)
                .flatMapMany(tp2 -> convertQueryResult(tp2.getT1(), tp2.getT2(), mapper));
        }
        return this
            .doScrollQuery(index, queryParam)
            .flatMap(tp2 -> convertQueryHit(tp2.getT1(), tp2.getT2(), mapper));
    }

    @Override
    public <T> Mono<PagerResult<T>> queryPager(String[] index, QueryParam queryParam, Function<Map<String, Object>, T> mapper) {
        if (!queryParam.isPaging()) {
            return Mono
                .zip(
                    this.count(index, queryParam),
                    this.query(index, queryParam, mapper).collectList(),
                    (total, list) -> PagerResult.of(total.intValue(), list, queryParam)
                )
                .switchIfEmpty(Mono.fromSupplier(PagerResult::empty));
        }
        return this
            .doQuery(index, queryParam)
            .flatMap(tp2 -> this
                .convertQueryResult(tp2.getT1(), tp2.getT2(), mapper)
                .collectList()
                .filter(CollectionUtils::isNotEmpty)
                .map(list -> PagerResult.of((int) tp2
                    .getT2()
                    .getHits()
                    .getTotalHits().value, list, queryParam))
            )
            .switchIfEmpty(Mono.fromSupplier(PagerResult::empty));
    }

    private <T> Flux<T> convertQueryResult(List<ElasticSearchIndexMetadata> indexList,
                                           SearchResponse response,
                                           Function<Map<String, Object>, T> mapper) {
        Map<String, ElasticSearchIndexMetadata> metadata = indexList
            .stream()
            .collect(Collectors.toMap(ElasticSearchIndexMetadata::getIndex, Function.identity()));

        return Flux
            .fromIterable(response.getHits())
            .map(hit -> {
                Map<String, Object> hitMap = hit.getSourceAsMap();
                if (StringUtils.isEmpty(hitMap.get("id"))) {
                    hitMap.put("id", hit.getId());
                }
                return mapper
                    .apply(Optional
                               .ofNullable(metadata.get(hit.getIndex())).orElse(indexList.get(0))
                               .convertFromElastic(hitMap));
            });

    }

    private <T> Flux<T> convertQueryHit(List<ElasticSearchIndexMetadata> indexList,
                                        SearchHit searchHit,
                                        Function<Map<String, Object>, T> mapper) {
        Map<String, ElasticSearchIndexMetadata> metadata = indexList
            .stream()
            .collect(Collectors.toMap(ElasticSearchIndexMetadata::getIndex, Function.identity()));

        return Flux
            .just(searchHit)
            .map(hit -> {
                Map<String, Object> hitMap = hit.getSourceAsMap();
                if (StringUtils.isEmpty(hitMap.get("id"))) {
                    hitMap.put("id", hit.getId());
                }
                return mapper
                    .apply(Optional
                               .ofNullable(metadata.get(hit.getIndex())).orElse(indexList.get(0))
                               .convertFromElastic(hitMap));
            });

    }

    private Mono<Tuple2<List<ElasticSearchIndexMetadata>, SearchResponse>> doQuery(String[] index,
                                                                                   QueryParam queryParam) {
        return indexManager
            .getIndexesMetadata(index)
            .collectList()
            .filter(CollectionUtils::isNotEmpty)
            .flatMap(metadataList -> this
                .createSearchRequest(queryParam, metadataList)
                .flatMap(restClient::searchForPage)
                .map(response -> Tuples.of(metadataList, response))
            )
            ;
    }

    private Flux<Tuple2<List<ElasticSearchIndexMetadata>, SearchHit>> doScrollQuery(String[] index,
                                                                                    QueryParam queryParam) {
        return indexManager
            .getIndexesMetadata(index)
            .collectList()
            .filter(CollectionUtils::isNotEmpty)
            .flatMapMany(metadataList -> this
                .createSearchRequest(queryParam, metadataList)
                .flatMapMany(restClient::scroll)
                .map(searchHit -> Tuples.of(metadataList, searchHit))
            );
    }


    @Override
    public Mono<Long> count(String[] index, QueryParam queryParam) {
        QueryParam param = queryParam.clone();
        param.setPaging(false);
        return this
            .createSearchRequest(param, index)
            .flatMap(this::doCount)
            .defaultIfEmpty(0L);
    }

    @Override
    public Mono<Long> delete(String index, QueryParam queryParam) {
        return this
            .getIndexForSearch(index)
            .flatMap(inx -> this
                .createQueryBuilder(queryParam, index)
                .flatMap(request -> restClient.deleteBy(delete -> delete.setQuery(request).indices(inx)))
                .map(BulkByScrollResponse::getDeleted))
            .defaultIfEmpty(0L);
    }

    private boolean checkWritable(String index) {
        if (SystemUtils.memoryIsOutOfWatermark()) {
            SystemUtils.printError("JVM内存不足,elasticsearch无法处理更多索引[%s]请求!", index);
            return false;
        }
        return true;
    }

    @Override
    public <T> Mono<Void> commit(String index, T payload) {
        if (checkWritable(index)) {
            sink.next(Buffer.of(index, payload));
        }
        return Mono.empty();
    }

    @Override
    public <T> Mono<Void> commit(String index, Collection<T> payload) {
        if (checkWritable(index)) {
            for (T t : payload) {
                sink.next(Buffer.of(index, t));
            }
        }
        return Mono.empty();
    }

    @Override
    public <T> Mono<Void> commit(String index, Publisher<T> data) {
        if (!checkWritable(index)) {
            return Mono.empty();
        }
        return Flux.from(data)
                   .flatMap(d -> commit(index, d))
                   .then();
    }

    @Override
    public <T> Mono<Void> save(String index, T payload) {
        return save(index, Mono.just(payload));
    }

    @Override
    public <T> Mono<Void> save(String index, Publisher<T> data) {
        return Flux.from(data)
                   .map(v -> Buffer.of(index, v))
                   .collectList()
                   .flatMap(this::doSave)
                   .then();
    }

    @Override
    public <T> Mono<Void> save(String index, Collection<T> payload) {
        return save(index, Flux.fromIterable(payload));
    }

    @PreDestroy
    public void shutdown() {
        sink.complete();
    }

    @Getter
    @Setter
    private BufferConfig buffer = new BufferConfig();

    @Getter
    @Setter
    public static class BufferConfig {
        //最小间隔
        private int rate = Integer.getInteger("elasticsearch.buffer.rate", 1000);
        //缓冲最大数量
        private int bufferSize = Integer.getInteger("elasticsearch.buffer.size", 3000);
        //缓冲超时时间
        private Duration bufferTimeout = Duration.ofSeconds(Integer.getInteger("elasticsearch.buffer.timeout", 3));
        //背压堆积数量限制.
        private int bufferBackpressure = Integer.getInteger("elasticsearch.buffer.backpressure", Runtime
            .getRuntime()
            .availableProcessors());
        //最大缓冲字节
        private DataSize bufferBytes = DataSize.parse(System.getProperty("elasticsearch.buffer.bytes", "15MB"));

        //最大重试次数
        private int maxRetry = 3;
        //重试间隔
        private Duration minBackoff = Duration.ofSeconds(3);

        private boolean refreshWhenWrite = false;
    }

    //@PostConstruct
    public void init() {
        int flushRate = buffer.rate;
        int bufferSize = buffer.bufferSize;
        Duration bufferTimeout = buffer.bufferTimeout;
        int bufferBackpressure = buffer.bufferBackpressure;
        long bufferBytes = buffer.bufferBytes.toBytes();
        AtomicLong bufferedBytes = new AtomicLong();

        FluxUtils
            .bufferRate(Flux.<Buffer>create(sink -> this.sink = sink),
                        flushRate,
                        bufferSize,
                        bufferTimeout,
                        (b, l) -> bufferedBytes.addAndGet(b.numberOfBytes()) >= bufferBytes)
            .doOnNext(buf -> bufferedBytes.set(0))
            .onBackpressureBuffer(bufferBackpressure, drop -> {
                // TODO: 2020/11/25 将丢弃的数据存储到本地磁盘
                drop.forEach(Buffer::release);
                SystemUtils.printError("elasticsearch无法处理更多索引请求!丢弃数据数量:%d", drop.size());
            }, BufferOverflowStrategy.DROP_OLDEST)
            .publishOn(Schedulers.boundedElastic(), bufferBackpressure)
            .flatMap(buffers -> {
                return Mono.create(sink -> {
                    try {
                        sink.onCancel(this
                                          .doSave(buffers)
                                          .doFinally((s) -> sink.success())
                                          .subscribe());
                    } catch (Exception e) {
                        sink.success();
                    }
                });
            })
            .onErrorResume((err) -> Mono
                .fromRunnable(() -> SystemUtils.printError("保存ElasticSearch数据失败:\n" +
                                                               org.hswebframework.utils.StringUtils.throwable2String(err))))
            .subscribe();
    }


    static ObjectPool<Buffer> pool = ObjectPool.newPool(Buffer::new);

    @Getter
    static class Buffer {
        String index;
        String id;
        String payload;
        final ObjectPool.Handle<Buffer> handle;

        public Buffer(ObjectPool.Handle<Buffer> handle) {
            this.handle = handle;
        }

        public static Buffer of(String index, Object payload) {
            Buffer buffer;
            try {
                buffer = pool.get();
            } catch (Throwable e) {
                buffer = new Buffer(null);
            }
            buffer.index = index;
            Map<String, Object> data = payload instanceof Map
                ? ((Map) payload) :
                FastBeanCopier.copy(payload, HashMap::new);
            Object id = data.get("id");
            buffer.id = id == null ? null : String.valueOf(id);
            buffer.payload = JSON.toJSONString(data);
            return buffer;
        }

        void release() {
            this.index = null;
            this.id = null;
            this.payload = null;
            if (null != handle) {
                handle.recycle(this);
            }
        }

        int numberOfBytes() {
            return payload == null ? 0 : payload.length() * 2;
        }
    }


    private Mono<String> getIndexForSave(String index) {
        return indexManager
            .getIndexStrategy(index)
            .map(strategy -> strategy.getIndexForSave(index));

    }

    private Mono<String> getIndexForSearch(String index) {
        return indexManager
            .getIndexStrategy(index)
            .map(strategy -> strategy.getIndexForSearch(index));

    }

    protected Mono<Integer> doSave(Collection<Buffer> buffers) {
        return Flux
            .fromIterable(buffers)
            .groupBy(Buffer::getIndex, Integer.MAX_VALUE)
            .flatMap(group -> {
                String index = group.key();
                return this
                    .getIndexForSave(index)
                    .flatMapMany(realIndex -> group
                        .map(buffer -> {
                            try {
                                IndexRequest request;
                                if (buffer.id != null) {
                                    request = new IndexRequest(realIndex).type("_doc").id(buffer.id);
                                } else {
                                    request = new IndexRequest(realIndex).type("_doc");
                                }
                                request.source(buffer.payload, XContentType.JSON);
                                return request;
                            } finally {
                                buffer.release();
                            }
                        }));
            })
            .collectList()
            .filter(CollectionUtils::isNotEmpty)
            .flatMap(lst -> {
                BulkRequest request = new BulkRequest();
                request.timeout(TimeValue.timeValueSeconds(9));
                if (buffer.refreshWhenWrite) {
                    request.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
                }
                lst.forEach(request::add);
                return restClient
                    .bulk(request)
                    .as(save -> {
                        if (buffer.maxRetry > 0) {
                            return save.retryWhen(Retry.backoff(buffer.maxRetry, buffer.minBackoff));
                        }
                        return save;
                    });
            })
            .doOnError((err) -> {
                //这里的错误都输出到控制台,输入到slf4j可能会造成日志递归.
                SystemUtils.printError("保存ElasticSearch数据失败:\n%s", () -> new Object[]{
                    org.hswebframework.utils.StringUtils.throwable2String(err)
                });
            })
            .doOnNext(response -> {
                log.trace("保存ElasticSearch数据成功,数量:{},耗时:{}", response.getItems().length, response.getTook());
                if (response.hasFailures()) {
                    SystemUtils.printError(response.buildFailureMessage());
                }
            })
            .thenReturn(buffers.size());
    }

    private <T> List<T> translate(Function<Map<String, Object>, T> mapper, SearchResponse response) {
        return Arrays.stream(response.getHits().getHits())
                     .map(hit -> {
                         Map<String, Object> hitMap = hit.getSourceAsMap();
                         if (StringUtils.isEmpty(hitMap.get("id"))) {
                             hitMap.put("id", hit.getId());
                         }
                         return mapper.apply(hitMap);
                     })
                     .collect(Collectors.toList());
    }

    private Mono<Long> doCount(SearchRequest request) {
        return restClient.count(request);
    }

    protected Mono<SearchRequest> createSearchRequest(QueryParam queryParam, String... indexes) {
        return indexManager
            .getIndexesMetadata(indexes)
            .collectList()
            .filter(CollectionUtils::isNotEmpty)
            .flatMap(list -> createSearchRequest(queryParam, list));
    }

    protected Mono<SearchRequest> createSearchRequest(QueryParam queryParam, List<ElasticSearchIndexMetadata> indexes) {

        SearchSourceBuilder builder = ElasticSearchConverter.convertSearchSourceBuilder(queryParam, indexes.get(0));
        return Flux.fromIterable(indexes)
                   .flatMap(index -> getIndexForSearch(index.getIndex()))
                   .collectList()
                   .map(indexList ->
                            new SearchRequest(indexList.toArray(new String[0]))
                                .source(builder)
                                .indicesOptions(indexOptions));
    }

    protected Mono<QueryBuilder> createQueryBuilder(QueryParam queryParam, String index) {
        return indexManager
            .getIndexMetadata(index)
            .map(metadata -> QueryParamTranslator.createQueryBuilder(queryParam, metadata))
            .switchIfEmpty(Mono.fromSupplier(() -> QueryParamTranslator.createQueryBuilder(queryParam, null)));
    }
}

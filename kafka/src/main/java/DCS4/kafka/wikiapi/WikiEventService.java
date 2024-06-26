package DCS4.kafka.wikiapi;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
@Slf4j
public class WikiEventService {

    private final WebClient webClient = WebClient.create("https://stream.wikimedia.org");
    private final KafkaTemplate<String, String> kafkaTemplate;
    private Disposable eventStreamDisposable;

    public WikiEventService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostConstruct
    public void subscribeWikiEvents() {
        Flux<ServerSentEvent<String>> eventStream = webClient.get()
                .uri("/v2/stream/mediawiki.page_change.v1")
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .timeout(Duration.ofSeconds(30))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(10))
                        .jitter(0.5));

        eventStreamDisposable = eventStream
                .doOnError(e -> log.error("evenstreamAPI 오류발생 : ", e))
                .doOnComplete(() -> log.error("stream 완료"))
                .subscribe(
                    content -> kafkaTemplate.send("wiki", content.data()),
                    error -> System.out.println("error = " + error),
                    () -> System.out.println("stream completed")
                );
    }

    @PreDestroy
    public void close() {
        log.info("WikiEventService is being closed.");
        if (eventStreamDisposable != null && !eventStreamDisposable.isDisposed()) {
            eventStreamDisposable.dispose();
            log.info("Wiki event stream subscription disposed");
        }
    }
}

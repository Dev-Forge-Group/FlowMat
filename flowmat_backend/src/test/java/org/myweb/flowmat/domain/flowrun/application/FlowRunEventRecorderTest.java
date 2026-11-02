package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunEvent;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunEventRepository;
import org.myweb.flowmat.global.id.IdGenerator;

/** Flow-run F19: the ledger sorts by time, so events recorded one after another must never share one. */
class FlowRunEventRecorderTest {
    private final FlowRunEventRepository repository = mock(FlowRunEventRepository.class);
    private final List<FlowRunEvent> saved = Collections.synchronizedList(new ArrayList<>());
    private final FlowRunEventRecorder recorder = new FlowRunEventRecorder(repository, new IdGenerator(), new ObjectMapper());

    @BeforeEach
    void keepSavedEvents() {
        when(repository.save(any(FlowRunEvent.class))).thenAnswer(call -> {
            saved.add(call.getArgument(0));
            return call.getArgument(0);
        });
    }

    @Test
    void eventsRecordedBackToBackGetLaterMicrosecondsEachTime() {
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < 2000; i++) {
            recorder.record("run", "step", i % 2 == 0 ? "step_completed" : "connection_filtered", null, null);
        }

        for (int i = 1; i < saved.size(); i++) {
            OffsetDateTime previous = saved.get(i - 1).getOccurredAt();
            OffsetDateTime current = saved.get(i).getOccurredAt();
            assertTrue(current.isAfter(previous), "event " + i + " at " + current + " is not after " + previous);
            assertEquals(0, current.getNano() % 1000, "Postgres keeps microseconds, so finer digits would be lost");
        }
        // A tie moves the time on by a microsecond, so 2,000 events stay within moments of the clock.
        assertTrue(Duration.between(before, saved.getLast().getOccurredAt()).compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    void eventsRecordedAtOnceFromManyRequestsStillGetTimesOfTheirOwn() throws Exception {
        ExecutorService requests = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int request = 0; request < 8; request++) {
                String runId = "run-" + request;
                done.add(requests.submit(() -> {
                    for (int i = 0; i < 500; i++) {
                        recorder.record(runId, null, "step_started", null, "user");
                    }
                }));
            }
            for (Future<?> request : done) {
                request.get();
            }
        } finally {
            requests.shutdown();
        }

        HashSet<OffsetDateTime> times = new HashSet<>();
        saved.forEach(event -> times.add(event.getOccurredAt()));
        assertEquals(4000, saved.size());
        assertEquals(4000, times.size());
    }
}

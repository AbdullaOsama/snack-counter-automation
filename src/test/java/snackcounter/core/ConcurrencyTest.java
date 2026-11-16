package snackcounter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import snackcounter.domain.OfficeId;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConcurrencyTest {

    @RepeatedTest(20)
    @DisplayName("Open call: when everybody presses 'accept' at the same moment, exactly one wins")
    void exactlyOneWinnerUnderRace() throws Exception {
        TestWorld w = new TestWorld(); // nobody in office -> open call, anyone may accept
        Refill r = w.service.reportEmpty(OfficeId.OSLO, "kari", List.of());
        assertEquals(RefillState.OPEN, r.state());

        List<String> people = List.of("anna", "bjorn", "carl", "asha", "ravi");
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(people.size());
        try {
            List<Future<Object>> futures = people.stream().map(p -> pool.submit(() -> {
                startGate.await();
                try {
                    w.service.accept(r.id(), p);
                    winners.incrementAndGet();
                } catch (RefillConflictException e) {
                    refused.incrementAndGet();
                }
                return null;
            })).toList();

            startGate.countDown();
            for (Future<Object> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get());
        assertEquals(people.size() - 1, refused.get());
        assertEquals(RefillState.COMMITTED, r.state());
    }
}

package snackcounter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import snackcounter.core.VolunteerPicker.Candidate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static snackcounter.core.TestWorld.ANNA;
import static snackcounter.core.TestWorld.BJORN;
import static snackcounter.core.TestWorld.CARL;
import static snackcounter.domain.OfficeId.OSLO;

class VolunteerPickerTest {

    private final VolunteerPicker picker = new VolunteerPicker();
    private static final Instant JAN = Instant.parse("2026-01-10T10:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-10T10:00:00Z");

    @Test
    @DisplayName("Fewest recent refills is asked first")
    void fewestRefillsFirst() {
        var pick = picker.pick(List.of(
                new Candidate(ANNA, 3, false, MAR),
                new Candidate(BJORN, 1, false, MAR),
                new Candidate(CARL, 2, false, JAN)));
        assertEquals(Optional.of(BJORN), pick);
    }

    @Test
    @DisplayName("Someone still waiting for their money is not asked to pay again if others can")
    void awaitingReimbursementGoesLast() {
        var pick = picker.pick(List.of(
                new Candidate(ANNA, 1, true, JAN),
                new Candidate(BJORN, 1, false, MAR)));
        assertEquals(Optional.of(BJORN), pick);
    }

    @Test
    @DisplayName("Ties: someone who never refilled, then the longest-ago refill, then id")
    void tieBreakers() {
        assertEquals(Optional.of(CARL), picker.pick(List.of(
                new Candidate(ANNA, 0, false, MAR),
                new Candidate(BJORN, 0, false, JAN),
                new Candidate(CARL, 0, false, null))));
        assertEquals(Optional.of(BJORN), picker.pick(List.of(
                new Candidate(ANNA, 0, false, MAR),
                new Candidate(BJORN, 0, false, JAN))));
        assertEquals(Optional.of(ANNA), picker.pick(List.of(
                new Candidate(BJORN, 0, false, null),
                new Candidate(ANNA, 0, false, null))));
    }

    @Test
    @DisplayName("No candidates -> nobody (caller falls back to an open call)")
    void empty() {
        assertEquals(Optional.empty(), picker.pick(List.of()));
    }

    @Test
    @DisplayName("End to end: refills rotate so the work is spread across everyone in the office")
    void rotationSpreadsWork() {
        TestWorld w = new TestWorld().usuallyAt(OSLO, "anna", "bjorn", "carl");
        Map<String, Integer> count = new TreeMap<>();
        for (int i = 0; i < 6; i++) {
            Refill r = w.buy(OSLO, "300");
            count.merge(r.purchaserId(), 1, Integer::sum);
            w.hr.approve(r.claimId());
            w.service.tick();
        }
        assertEquals(Map.of("anna", 2, "bjorn", 2, "carl", 2), count);
    }
}

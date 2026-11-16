package snackcounter.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * What the volunteer uploads after buying. {@code items} is a free-text list of what was bought,
 * used only for the monthly "what do we buy" summary.
 */
public record Receipt(Money total, LocalDate purchaseDate, List<String> items, byte[] image) {

    public Receipt {
        items = List.copyOf(items);
    }
}

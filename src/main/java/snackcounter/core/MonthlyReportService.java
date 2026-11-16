package snackcounter.core;

import snackcounter.domain.Money;
import snackcounter.domain.OfficeConfig;
import snackcounter.ports.FxRates;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Builds the monthly report from the refills themselves (no separate bookkeeping to drift out of sync). */
public final class MonthlyReportService {

    private final RefillService service;
    private final FxRates fx;

    public MonthlyReportService(RefillService service, FxRates fx) {
        this.service = service;
        this.fx = fx;
    }

    public MonthlyReport build(YearMonth month, Currency reportingCurrency) {
        List<Refill> all = service.refills();
        List<MonthlyReport.OfficeLine> lines = new ArrayList<>();
        Money total = Money.zero(reportingCurrency);
        Map<String, Integer> byVolunteer = new TreeMap<>();
        Map<String, Integer> ranOut = new TreeMap<>();

        for (OfficeConfig office : service.offices()) {
            List<Refill> bought = all.stream()
                    .filter(r -> r.office() == office.id())
                    .filter(r -> r.state().countsAgainstBudget())
                    .filter(r -> YearMonth.from(r.receipt().purchaseDate()).equals(month))
                    .toList();

            Money spent = Money.zero(office.currency());
            Money reimbursed = Money.zero(office.currency());
            Money owed = Money.zero(office.currency());
            Duration emptyToRefilled = Duration.ZERO;
            for (Refill r : bought) {
                Money amount = r.receipt().total();
                spent = spent.plus(amount);
                if (r.state() == RefillState.REIMBURSED) {
                    reimbursed = reimbursed.plus(amount);
                } else {
                    owed = owed.plus(amount);
                }
                emptyToRefilled = emptyToRefilled.plus(Duration.between(r.reportedAt(), r.purchasedAt()));
                byVolunteer.merge(service.volunteer(r.purchaserId()).name(), 1, Integer::sum);
            }

            all.stream()
                    .filter(r -> r.office() == office.id())
                    .filter(r -> YearMonth.from(r.reportedAt().atZone(office.zone())).equals(month))
                    .flatMap(r -> r.ranOutItems().stream())
                    .forEach(item -> ranOut.merge(item.toLowerCase(), 1, Integer::sum));

            BigDecimal rate = fx.rate(office.currency(), reportingCurrency, month);
            Money converted = fx.convert(spent, reportingCurrency, month);
            total = total.plus(converted);

            lines.add(new MonthlyReport.OfficeLine(office.id(), office.monthlyBudget(), spent,
                    office.monthlyBudget().minus(spent), rate, converted, bought.size(), reimbursed, owed,
                    bought.isEmpty() ? null : emptyToRefilled.dividedBy(bought.size())));
        }
        return new MonthlyReport(month, reportingCurrency, lines, total, byVolunteer, topFive(ranOut));
    }

    private static Map<String, Integer> topFive(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }
}

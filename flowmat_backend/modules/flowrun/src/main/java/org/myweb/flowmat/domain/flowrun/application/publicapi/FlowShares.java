package org.myweb.flowmat.domain.flowrun.application.publicapi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a quantity is divided at a Split and lost along a connection when a run routes it (ADR-008, ADR-009). Runs
 * (FlowRunGraph) and the forward check of planning ({@link FlowDemand}, ADR-043 decision 3) use these, so a plan's amounts
 * are what a run delivers.
 */
public final class FlowShares {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private FlowShares() {
    }

    /** One connection out of a Split: {@code kind} is {@code share} (percent of the whole), {@code fixed} or {@code rest}. */
    public record Share(String connectionId, String kind, BigDecimal value) {
    }

    /**
     * What arrives after a loss {@code rate} (0 &le; rate &lt; 1) on a positive {@code sent}: rounded to 4 decimals HALF_UP, and
     * no more than was sent (ADR-009 decision 3).
     */
    public static BigDecimal arrived(BigDecimal sent, BigDecimal rate) {
        BigDecimal arrived = sent.multiply(BigDecimal.ONE.subtract(rate)).setScale(4, RoundingMode.HALF_UP);
        // A quantity with more than 4 decimals can round up past itself.
        return arrived.compareTo(sent) > 0 ? sent : arrived;
    }

    /**
     * A Split's amounts by connection, in the order drawn; {@code given} is what the fixed and percent shares took before what was
     * left went to the rest or the last percent.
     */
    public record Division(Map<String, BigDecimal> amounts, BigDecimal given) {
    }

    /**
     * A Split's division of {@code total} (ADR-008 decisions 2-7): fixed quantities, percents of the whole rounded to 4 decimals
     * HALF_UP, and the rest. What is left goes to the rest, else to the last percent drawn; with neither it is given to no one, and
     * the caller decides. Amounts can come out negative when the fixed and percent shares ask for more than the total; the caller
     * refuses that. A share or fixed connection without its value is an IllegalArgumentException whose message is the
     * connection's id.
     */
    public static Division split(BigDecimal total, List<Share> shares) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        BigDecimal assigned = BigDecimal.ZERO;
        String rest = null;
        String lastShare = null;
        for (Share share : shares) {
            if ("rest".equals(share.kind())) {
                rest = share.connectionId();
                amounts.put(share.connectionId(), null);
                continue;
            }
            BigDecimal amount;
            if ("share".equals(share.kind()) && share.value() != null) {
                amount = total.multiply(share.value()).divide(HUNDRED, 4, RoundingMode.HALF_UP);
                lastShare = share.connectionId();
            } else if ("fixed".equals(share.kind()) && share.value() != null) {
                amount = share.value();
            } else {
                throw new IllegalArgumentException(share.connectionId());
            }
            amounts.put(share.connectionId(), amount);
            assigned = assigned.add(amount);
        }
        BigDecimal left = total.subtract(assigned);
        if (rest != null) {
            amounts.put(rest, left);
        } else if (lastShare != null) {
            amounts.merge(lastShare, left, BigDecimal::add);
        }
        return new Division(amounts, assigned);
    }
}

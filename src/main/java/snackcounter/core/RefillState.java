package snackcounter.core;

import java.util.EnumSet;

/**
 * The refill lifecycle. Every state change goes through {@link #canMoveTo}, so an illegal
 * jump (e.g. REIMBURSED back to COMMITTED) is impossible rather than merely unlikely.
 *
 * <pre>
 *  OPEN ──offer──▶ OFFERED ──accept──▶ COMMITTED ──receipt──▶ PURCHASED ──▶ CLAIM_SUBMITTED ──▶ REIMBURSED
 *   │  ▲             │ timeout/decline     │ timeout/back out     ▲              │
 *   │  └─────────────┴─────────────────────┘ (next volunteer)     │              └──▶ CLAIM_REJECTED
 *   │                                      receipt over limit ──▶ NEEDS_APPROVAL ─┘ (admin approves)
 *   └──▶ ON_HOLD_BUDGET (not enough budget left this month; reopens next month)
 * </pre>
 */
public enum RefillState {
    OPEN,
    OFFERED,
    COMMITTED,
    ON_HOLD_BUDGET,
    NEEDS_APPROVAL,
    PURCHASED,
    CLAIM_SUBMITTED,
    REIMBURSED,
    CLAIM_REJECTED,
    CANCELLED;

    public boolean canMoveTo(RefillState next) {
        return switch (this) {
            case OPEN -> EnumSet.of(OPEN, OFFERED, COMMITTED, ON_HOLD_BUDGET, CANCELLED).contains(next);
            case ON_HOLD_BUDGET -> EnumSet.of(OPEN, CANCELLED).contains(next);
            case OFFERED -> EnumSet.of(OFFERED, OPEN, COMMITTED, CANCELLED).contains(next);
            case COMMITTED -> EnumSet.of(OFFERED, OPEN, PURCHASED, NEEDS_APPROVAL, CANCELLED).contains(next);
            case NEEDS_APPROVAL -> EnumSet.of(PURCHASED, CLAIM_REJECTED).contains(next);
            case PURCHASED -> next == CLAIM_SUBMITTED;
            case CLAIM_SUBMITTED -> next == REIMBURSED || next == CLAIM_REJECTED;
            case REIMBURSED, CLAIM_REJECTED, CANCELLED -> false;
        };
    }

    /** The box is still empty: only one such refill may exist per office (prevents double buying). */
    public boolean boxStillEmpty() {
        return this == OPEN || this == OFFERED || this == COMMITTED || this == ON_HOLD_BUDGET;
    }

    /** Money has been spent (or is about to be reimbursed) and must count against the monthly budget. */
    public boolean countsAgainstBudget() {
        return this == NEEDS_APPROVAL || this == PURCHASED || this == CLAIM_SUBMITTED || this == REIMBURSED;
    }

    /** The volunteer has paid out of pocket and is still waiting for their money. */
    public boolean awaitingReimbursement() {
        return this == NEEDS_APPROVAL || this == PURCHASED || this == CLAIM_SUBMITTED;
    }
}

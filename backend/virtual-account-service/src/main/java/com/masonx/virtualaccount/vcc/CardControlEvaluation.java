package com.masonx.virtualaccount.vcc;

public record CardControlEvaluation(boolean approved, String declineReason) {

    public static CardControlEvaluation allow() {
        return new CardControlEvaluation(true, null);
    }

    public static CardControlEvaluation declined(String declineReason) {
        return new CardControlEvaluation(false, declineReason);
    }
}

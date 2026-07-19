package com.masonx.common.id;

/**
 * Canonical MasonXPay prefixes for public, service-minted Snowflake IDs.
 *
 * This enum owns names and literal prefixes only. Callers choose the correct
 * prefix for the resource they are minting.
 */
public enum MasonXIdPrefix {
    ADMIN_AUDIT_LOG("aal_"),
    ADMIN_USER("adm_"),
    API_KEY("ak_"),
    BILLING_CUSTOMER("cus_"),
    CARD_AUTHORIZATION("cauth_"),
    CARD_CLOSE_TRANSACTION("tx_close_"),
    CARD_FUND_TRANSACTION("tx_fund_"),
    CUSTOMER_PAYMENT_METHOD("cpm_"),
    DISPUTE("dp_"),
    DISPUTE_EVIDENCE_FILE("def_"),
    EVENT("evt_"),
    GATEWAY_LOG("glog_"),
    INVITE_TOKEN("it_"),
    INVOICE("inv_"),
    INVOICE_PAYMENT_ATTEMPT("ipa_"),
    ISO20022_END_TO_END("e2e_"),
    ISO20022_INSTRUCTION("ins_"),
    ISO20022_LOG("i20_"),
    ISO20022_MESSAGE("m20_"),
    ISO8583_LOG("iso_"),
    LEDGER_ENTRY("le_"),
    LEDGER_RAIL_TRANSACTION("tx_rail_"),
    LEDGER_TRANSACTION("tx_"),
    MERCHANT("mer_"),
    MERCHANT_AUDIT_LOG("mal_"),
    MERCHANT_USER("mu_"),
    NETWORK_CORRELATION("corr_"),
    ORGANIZATION("org_"),
    ORGANIZATION_USER("ou_"),
    PAYMENT_INSTRUMENT("pinst_"),
    PAYMENT_INTENT("pi_"),
    PAYMENT_LINK("plink_"),
    PAYMENT_REQUEST("pr_"),
    PAYMENT_TOKEN("ptok_"),
    PROVIDER_ACCOUNT("pa_"),
    RAIL_PAYMENT("rp_"),
    RAIL_ROUTING_DECISION("rd_"),
    REFRESH_TOKEN("rt_"),
    REFUND("rf_"),
    REVERSAL_TASK("rtask_"),
    ROUTE_POLICY("rpol_"),
    ROUTE_POLICY_AUDIT_LOG("rpa_"),
    ROUTE_POLICY_ROUTE("rroute_"),
    ROUTE_POLICY_STEP("rstep_"),
    ROUTING_ATTRIBUTE("rattr_"),
    ROUTING_RULE("rr_"),
    SCHEDULED_RETRY_JOB("retry_"),
    SETTLEMENT_EXCEPTION("sexc_"),
    SUBSCRIPTION("sub_"),
    SUBSCRIPTION_CHECKOUT_LINK("scl_"),
    SUBSCRIPTION_ITEM("si_"),
    USER("usr_"),
    VA_ACCOUNT("ac_"),
    VCC_ACCOUNT("va_"),
    VIRTUAL_CARD("vc_"),
    WEBHOOK_DELIVERY("whd_"),
    WEBHOOK_ENDPOINT("whe_");

    private final String prefix;

    MasonXIdPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}

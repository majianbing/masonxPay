package com.masonx.virtualaccount.domain.constant;

/**
 * Ledger entry direction from the perspective of the affected account.
 *
 * <p>Debit and credit are accounting directions, not direct synonyms for
 * increase and decrease. Whether a direction increases a balance depends on
 * the account's {@link NormalBalance}:
 *
 * <ul>
 *   <li>DEBIT-normal accounts increase on DEBIT and decrease on CREDIT.
 *       Typical examples: CASH, receivables, assets.
 *   <li>CREDIT-normal accounts increase on CREDIT and decrease on DEBIT.
 *       Typical examples: liabilities, income/revenue, payables.
 * </ul>
 *
 * <p>MasonXPay examples:
 *
 * <ul>
 *   <li>Merchant WALLET is CREDIT-normal because it is money the platform owes
 *       the merchant. Crediting WALLET increases the merchant balance; debiting
 *       WALLET decreases it.
 *   <li>MERCHANT_RECEIVABLE is DEBIT-normal because it is money the merchant
 *       owes the platform. Debiting MERCHANT_RECEIVABLE increases merchant debt;
 *       crediting it pays the debt down.
 *   <li>Card funding moves liability from wallet to card:
 *       DEBIT WALLET / CREDIT PREPAID_CARD.
 * </ul>
 */
public enum Direction {
    /** Debit. Increases DEBIT-normal accounts; decreases CREDIT-normal accounts. */
    DEBIT,

    /** Credit. Increases CREDIT-normal accounts; decreases DEBIT-normal accounts. */
    CREDIT
}

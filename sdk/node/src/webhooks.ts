import { createHmac, timingSafeEqual } from 'crypto';
import { GatewayError } from './http';
import { GatewayEvent } from './types';

/**
 * Tolerance window for timestamp validation — 5 minutes.
 */
const TIMESTAMP_TOLERANCE_SECONDS = 5 * 60;

export class WebhooksResource {
  /**
   * Verifies the `X-Gateway-Signature` header and returns the parsed event payload.
   *
   * Signature format:  t=<unix_timestamp>,v1=<hex(HMAC-SHA256(secret, "t=<ts>.<body>"))>
   *
   * @param payload       Raw request body string (do NOT parse before passing)
   * @param signatureHeader  Value of the `X-Gateway-Signature` header
   * @param signingSecret    The endpoint's signing secret (e.g. "whsec_...")
   * @throws GatewayError if the signature is invalid or the timestamp is stale
   */
  verify<T = unknown>(
    payload: string,
    signatureHeader: string,
    signingSecret: string,
  ): GatewayEvent<T> {
    const parts = this.parseHeader(signatureHeader);

    const nowSeconds = Math.floor(Date.now() / 1000);
    if (Math.abs(nowSeconds - parts.timestamp) > TIMESTAMP_TOLERANCE_SECONDS) {
      throw new GatewayError(400, 'Webhook timestamp too old or in the future');
    }

    const signedPayload = `t=${parts.timestamp}.${payload}`;
    const expected = createHmac('sha256', signingSecret)
      .update(signedPayload, 'utf8')
      .digest('hex');

    const expectedBuf = Buffer.from(expected, 'hex');
    const receivedBuf = Buffer.from(parts.v1, 'hex');

    if (
      expectedBuf.length !== receivedBuf.length ||
      !timingSafeEqual(expectedBuf, receivedBuf)
    ) {
      throw new GatewayError(400, 'Webhook signature verification failed');
    }

    return JSON.parse(payload) as GatewayEvent<T>;
  }

  private parseHeader(header: string): { timestamp: number; v1: string } {
    const map: Record<string, string> = {};
    for (const part of header.split(',')) {
      const idx = part.indexOf('=');
      if (idx === -1) continue;
      map[part.slice(0, idx)] = part.slice(idx + 1);
    }

    const t = map['t'];
    const v1 = map['v1'];

    if (!t || !v1) {
      throw new GatewayError(400, 'Invalid webhook signature header format');
    }

    const timestamp = parseInt(t, 10);
    if (isNaN(timestamp)) {
      throw new GatewayError(400, 'Invalid webhook timestamp');
    }

    return { timestamp, v1 };
  }
}

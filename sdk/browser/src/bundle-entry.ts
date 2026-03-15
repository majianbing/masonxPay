// Entry point for the IIFE bundle (gateway-sdk.min.js).
// Assigns GatewayEmbedded to window so it's available as a global after a <script> tag.
import { GatewayEmbedded } from './index';
(globalThis as unknown as Record<string, unknown>).GatewayEmbedded = GatewayEmbedded;

import type { NextConfig } from "next";

// "standalone" is required for Docker (self-hosted) deployments.
// Vercel has its own build pipeline and does not support standalone output —
// omit it there by setting NEXT_STANDALONE=false (or leaving it unset).
const nextConfig: NextConfig = {
  ...(process.env.NEXT_STANDALONE === "true" ? { output: "standalone" } : {}),
  transpilePackages: ['@gateway/browser'],
};

export default nextConfig;

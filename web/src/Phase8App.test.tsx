import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Phase8App } from "./Phase8App";

beforeEach(() => {
  globalThis.fetch = vi.fn().mockImplementation((url: string) =>
    Promise.resolve({
      ok: true,
      json: async () =>
        url.includes("market-wide/status")
          ? {
              running: false,
              completedRuns: 2,
              failedRuns: 0,
              skippedRuns: 1,
              lastDurationMillis: 1200,
              lastCandidateCount: 12,
              rankingSources: [],
              enrichment: { enabled: true, capacity: 200, retainedJobs: 4, states: { QUEUED: 4 },
                accepted: 5, coalesced: 2, succeeded: 1, exhausted: 0, expired: 0, rejected: 0,
                lastProcessedAt: null, lastError: null },
              collection: {
                detailQuoteBudget: 30, restLookups: 7, restFailures: 1,
                insufficientCount: 2, maxDataAgeSeconds: 12,
                dataSources: { CACHE: 5 },
                kisRequests: { requests: 20, rateLimitRetries: 1, rateLimitErrors: 1 },
              },
            }
          : url.includes("market-wide")
          ? {
              scannedAt: "2026-09-03T02:00:00Z",
              market: "ALL",
              requestedLimit: 40,
              scannedCount: 0,
              candidateCount: 0,
              fallback: false,
              rankingSources: [],
              precisionAllocation: {
                state: "DISABLED",
                capacity: 28,
                activeCount: 0,
                remainingSlots: 41,
                reservedSlots: 3,
                allocations: [],
              },
              universe: {
                activeStocks: 0,
                tradableStocks: 0,
                realtimeSubscriptionLimit: 41,
                realtimeSubscriptionCount: 0,
                realtimeSubscriptionRemaining: 41,
              },
              regime: {
                state: "UNKNOWN",
                averageChangeRate: null,
                advanceRate: null,
                declineRate: null,
                averageTradingValue: null,
              },
              candidates: [],
            }
          : [],
    }),
  ) as typeof fetch;
});

test("opens market-wide scanner", async () => {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <Phase8App />
    </QueryClientProvider>,
  );
  fireEvent.click(screen.getByRole("button", { name: /시장 전체/ }));
  expect(
    screen.getByRole("heading", { name: "시장 전체 스캐너" }),
  ).toBeInTheDocument();
  expect(screen.getByText("사용 안내")).toBeInTheDocument();
  expect(await screen.findByText("후보가 없습니다")).toBeInTheDocument();
  expect(await screen.findByText(/상세 REST 조회 7\/30종목/)).toBeInTheDocument();
  expect(screen.getByText(/CACHE 5건/)).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '비동기 후보 보강' })).toBeInTheDocument();
  expect(screen.getByText(/보관 작업 4\/200건/)).toBeInTheDocument();
});

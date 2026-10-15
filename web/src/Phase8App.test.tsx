/**
 * 시장 전체 스캐너 화면(MarketWidePage) 테스트예요. fetch를 가짜 함수로 바꿔 상태·스캔 결과를 주소별로 돌려줘요.
 * 빈 후보 안내, 수집 통계(상세 REST 조회, 데이터 출처), 비동기 후보 보강 상태가 보이는지 확인해요.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MarketWidePage } from "./Phase8App";

// 주소가 market-wide/status면 수집기 상태를, market-wide면 후보 0건 스캔 결과를, 그 밖에는 빈 목록을 돌려줘요.
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
      <MarketWidePage />
    </QueryClientProvider>,
  );
  expect(
    screen.getByRole("heading", { name: "시장 전체 스캐너" }),
  ).toBeInTheDocument();
  expect(screen.getByText("사용 안내")).toBeInTheDocument();
  // 화면을 여는 것만으로는 스캔(KIS 조회)을 하지 않아요.
  expect(screen.getByText(/아직 수동 스캔을 실행하지 않았습니다/)).toBeInTheDocument();
  expect(vi.mocked(globalThis.fetch).mock.calls.some(([url]) => String(url).includes("/market-wide/scan"))).toBe(false);
  fireEvent.click(screen.getByRole("button", { name: "넓은 범위 스캔" }));
  expect(await screen.findByText("후보가 없습니다")).toBeInTheDocument();
  expect(vi.mocked(globalThis.fetch).mock.calls.some(([url]) => String(url).includes("/market-wide/scan"))).toBe(true);
  expect(await screen.findByText(/상세 REST 조회 7\/30종목/)).toBeInTheDocument();
  expect(screen.getByText(/CACHE 5건/)).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '비동기 후보 보강' })).toBeInTheDocument();
  expect(screen.getByText(/보관 작업 4\/200건/)).toBeInTheDocument();
});

import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Phase8App } from "./Phase8App";

beforeEach(() => {
  globalThis.fetch = vi.fn().mockImplementation((url: string) =>
    Promise.resolve({
      ok: true,
      json: async () =>
        url.includes("market-wide")
          ? {
              scannedAt: "2026-09-03T02:00:00Z",
              market: "ALL",
              requestedLimit: 40,
              scannedCount: 0,
              candidateCount: 0,
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
});

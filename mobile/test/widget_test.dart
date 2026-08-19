import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stock_platform_mobile/core/api_client.dart';
import 'package:stock_platform_mobile/core/repositories.dart';
import 'package:stock_platform_mobile/main.dart';

void main() {
  testWidgets('renders phase 8 mobile shell', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        marketEventProvider.overrideWith((ref) => const Stream.empty()),
        watchlistsProvider.overrideWith((ref) async => []),
        detectionProvider.overrideWith((ref, type) async => []),
      ],
      child: const StockPlatformApp(),
    ));
    expect(find.text('오늘의 시장'), findsOneWidget);
    expect(find.text('홈'), findsOneWidget);
    expect(find.text('Scanner'), findsOneWidget);
  });
}

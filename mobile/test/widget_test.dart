// 모바일 앱 껍데기 화면이 뜨는지 확인하는 위젯 테스트예요.
// 네트워크 대신 provider 결과를 가짜(빈 값)로 바꿔 서버 없이 실행해요.
// flutter test 명령으로 실행돼요.
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stock_platform_mobile/core/api_client.dart';
import 'package:stock_platform_mobile/core/repositories.dart';
import 'package:stock_platform_mobile/main.dart';

/// 테스트 진입점이에요. 받는 것·돌려주는 것 없음.
void main() {
  testWidgets('renders phase 8 mobile shell', (tester) async {
    // overrides로 SSE 스트림·관심종목·탐지 provider를 빈 결과로 바꿔 실제 HTTP 호출을 막아요.
    await tester.pumpWidget(ProviderScope(
      overrides: [
        marketEventProvider.overrideWith((ref) => const Stream.empty()),
        watchlistsProvider.overrideWith((ref) async => []),
        detectionProvider.overrideWith((ref, type) async => []),
      ],
      child: const StockPlatformApp(),
    ));
    // 홈 화면 제목과 하단 탭 이름이 보이면 앱 틀이 정상으로 그려진 것으로 판단해요.
    expect(find.text('오늘의 시장'), findsOneWidget);
    expect(find.text('홈'), findsOneWidget);
    expect(find.text('Scanner'), findsOneWidget);
  });
}

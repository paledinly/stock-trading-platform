// 모바일 앱의 시작점(엔트리 포인트)이에요.
// Flutter 엔진을 준비한 뒤 Riverpod(상태를 앱 전체에 나눠 주는 도구) 범위로 앱을 감싸 실행해요.
// 운영체제가 앱을 켤 때 가장 먼저 호출하고, 테스트는 여기서 다시 내보내는 StockPlatformApp을 써요.
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'app/app.dart';
export 'app/app.dart' show StockPlatformApp;

/// 앱을 실행하는 함수예요.
/// 받는 것: 없음. 돌려주는 것: 없음(화면에 앱을 띄워요).
void main() {
  // runApp 전에 플러그인 등 플랫폼 기능을 쓸 수 있도록 바인딩을 먼저 초기화해요.
  WidgetsFlutterBinding.ensureInitialized();
  // ProviderScope는 Riverpod provider들이 값을 보관하는 상자예요. 앱 맨 바깥에 하나 있어야 해요.
  runApp(const ProviderScope(child: StockPlatformApp()));
}

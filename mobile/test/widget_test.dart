import 'package:flutter_test/flutter_test.dart';
import 'package:stock_platform_mobile/main.dart';

void main() {
  testWidgets('renders foundation status', (tester) async {
    await tester.pumpWidget(const StockPlatformApp());
    expect(find.text('Foundation ready'), findsOneWidget);
  });
}


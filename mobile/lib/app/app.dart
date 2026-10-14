// 앱의 화면 길찾기(라우팅)와 공통 틀을 정의하는 파일이에요.
// GoRouter(주소 문자열로 화면을 바꿔 주는 라우팅 도구)로 경로별 화면을 연결하고, 아래쪽 탭 메뉴가 있는 껍데기 화면을 만들어요.
// main.dart와 위젯 테스트가 StockPlatformApp을 사용해요.
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../features/screens.dart';

/// 경로와 화면의 대응표(GoRouter)를 Riverpod provider(어디서나 꺼내 쓰는 공용 값)로 제공해요.
/// 홈·관심·탐지·Scanner·노트 다섯 탭은 ShellRoute 안에 있어서 아래 탭 메뉴를 함께 보여 줘요.
/// 검색과 종목 상세는 탭 메뉴 없이 전체 화면으로 열려요.
final routerProvider = Provider<GoRouter>((ref) => GoRouter(initialLocation: '/', routes: [
  ShellRoute(builder: (context, state, child) => AppShell(child: child), routes: [
    GoRoute(path: '/', builder: (_, __) => const HomeScreen()),
    GoRoute(path: '/watchlists', builder: (_, __) => const WatchlistScreen()),
    GoRoute(path: '/detections', builder: (_, __) => const DetectionScreen()),
    GoRoute(path: '/scanners', builder: (_, __) => const ScannerScreen()),
    GoRoute(path: '/journal', builder: (_, __) => const JournalScreen()),
  ]),
  GoRoute(path: '/search', builder: (_, __) => const StockSearchScreen()),
  // 종목 상세는 주소의 code와 쿼리의 name·market을 읽어요. 이름이 없으면 코드를 대신 보여 줘요.
  GoRoute(path: '/stocks/:code', builder: (_, state) => StockDetailScreen(code: state.pathParameters['code']!, name: state.uri.queryParameters['name'] ?? state.pathParameters['code']!, market: state.uri.queryParameters['market'] ?? '')),
]));

/// 앱 전체를 감싸는 최상위 위젯이에요. 건물의 정문과 안내 데스크처럼 테마와 길 안내(라우터)를 정해요.
/// 받는 것: 없음. 돌려주는 것: 어두운 테마와 라우터가 적용된 MaterialApp 화면.
class StockPlatformApp extends ConsumerWidget {
  const StockPlatformApp({super.key});
  /// ConsumerWidget(Riverpod 값을 읽을 수 있는 위젯)이라 ref로 routerProvider를 읽어 라우터를 연결해요.
  @override Widget build(BuildContext context, WidgetRef ref) => MaterialApp.router(
    title: 'Stock Track', debugShowCheckedModeBanner: false,
    theme: ThemeData(brightness: Brightness.dark, useMaterial3: true, colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff4f82e9), brightness: Brightness.dark), scaffoldBackgroundColor: const Color(0xff090e17), cardTheme: const CardThemeData(color: Color(0xff111927), margin: EdgeInsets.zero), inputDecorationTheme: const InputDecorationTheme(filled: true, fillColor: Color(0xff141c2a), border: OutlineInputBorder())),
    routerConfig: ref.watch(routerProvider),
  );
}

/// 탭 화면 공통 틀이에요. 책의 표지와 목차 띠처럼 내용(child)을 감싸고 아래쪽 탭 메뉴를 붙여요.
/// 받는 것: child(현재 탭 화면). 돌려주는 것: 본문과 하단 NavigationBar가 있는 Scaffold.
/// 현재 주소가 탭 목록에 없으면 첫 번째 탭(홈)을 선택된 것으로 표시해요.
class AppShell extends StatelessWidget {
  const AppShell({required this.child, super.key}); final Widget child;
  @override Widget build(BuildContext context) { final path=GoRouterState.of(context).uri.path; const paths=['/','/watchlists','/detections','/scanners','/journal']; final found=paths.indexOf(path); final index=found<0?0:found; return Scaffold(body: SafeArea(child: child),bottomNavigationBar: NavigationBar(selectedIndex:index,onDestinationSelected:(value)=>context.go(paths[value]),destinations:const[NavigationDestination(icon:Icon(Icons.home_outlined),selectedIcon:Icon(Icons.home),label:'홈'),NavigationDestination(icon:Icon(Icons.star_outline),selectedIcon:Icon(Icons.star),label:'관심'),NavigationDestination(icon:Icon(Icons.bolt_outlined),selectedIcon:Icon(Icons.bolt),label:'탐지'),NavigationDestination(icon:Icon(Icons.query_stats),label:'Scanner'),NavigationDestination(icon:Icon(Icons.menu_book_outlined),selectedIcon:Icon(Icons.menu_book),label:'노트')])); }
}

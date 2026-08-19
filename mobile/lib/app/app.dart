import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../features/screens.dart';

final routerProvider = Provider<GoRouter>((ref) => GoRouter(initialLocation: '/', routes: [
  ShellRoute(builder: (context, state, child) => AppShell(child: child), routes: [
    GoRoute(path: '/', builder: (_, __) => const HomeScreen()),
    GoRoute(path: '/watchlists', builder: (_, __) => const WatchlistScreen()),
    GoRoute(path: '/detections', builder: (_, __) => const DetectionScreen()),
    GoRoute(path: '/scanners', builder: (_, __) => const ScannerScreen()),
    GoRoute(path: '/journal', builder: (_, __) => const JournalScreen()),
  ]),
  GoRoute(path: '/search', builder: (_, __) => const StockSearchScreen()),
  GoRoute(path: '/stocks/:code', builder: (_, state) => StockDetailScreen(code: state.pathParameters['code']!, name: state.uri.queryParameters['name'] ?? state.pathParameters['code']!, market: state.uri.queryParameters['market'] ?? '')),
]));

class StockPlatformApp extends ConsumerWidget {
  const StockPlatformApp({super.key});
  @override Widget build(BuildContext context, WidgetRef ref) => MaterialApp.router(
    title: 'Stock Track', debugShowCheckedModeBanner: false,
    theme: ThemeData(brightness: Brightness.dark, useMaterial3: true, colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff4f82e9), brightness: Brightness.dark), scaffoldBackgroundColor: const Color(0xff090e17), cardTheme: const CardThemeData(color: Color(0xff111927), margin: EdgeInsets.zero), inputDecorationTheme: const InputDecorationTheme(filled: true, fillColor: Color(0xff141c2a), border: OutlineInputBorder())),
    routerConfig: ref.watch(routerProvider),
  );
}

class AppShell extends StatelessWidget {
  const AppShell({required this.child, super.key}); final Widget child;
  @override Widget build(BuildContext context) { final path=GoRouterState.of(context).uri.path; const paths=['/','/watchlists','/detections','/scanners','/journal']; final found=paths.indexOf(path); final index=found<0?0:found; return Scaffold(body: SafeArea(child: child),bottomNavigationBar: NavigationBar(selectedIndex:index,onDestinationSelected:(value)=>context.go(paths[value]),destinations:const[NavigationDestination(icon:Icon(Icons.home_outlined),selectedIcon:Icon(Icons.home),label:'홈'),NavigationDestination(icon:Icon(Icons.star_outline),selectedIcon:Icon(Icons.star),label:'관심'),NavigationDestination(icon:Icon(Icons.bolt_outlined),selectedIcon:Icon(Icons.bolt),label:'탐지'),NavigationDestination(icon:Icon(Icons.query_stats),label:'Scanner'),NavigationDestination(icon:Icon(Icons.menu_book_outlined),selectedIcon:Icon(Icons.menu_book),label:'노트')])); }
}

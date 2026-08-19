import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../core/api_client.dart';
import '../core/models.dart';
import '../core/repositories.dart';

String money(num value) =>
    '${value.round().toString().replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (_) => ',')}원';
void openStock(BuildContext context, Stock stock) => context.push(
    '/stocks/${stock.code}?name=${Uri.encodeComponent(stock.name)}&market=${stock.market}');

class AsyncPane<T> extends StatelessWidget {
  const AsyncPane({required this.value, required this.data, super.key});
  final AsyncValue<T> value;
  final Widget Function(T) data;
  @override
  Widget build(BuildContext context) => value.when(
      data: data,
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (error, _) => Center(
          child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(
                  error is DioException ? error.message ?? '네트워크 오류' : '$error',
                  textAlign: TextAlign.center))));
}

class PageHeader extends StatelessWidget {
  const PageHeader(this.title, {this.subtitle, this.action, super.key});
  final String title;
  final String? subtitle;
  final Widget? action;
  @override
  Widget build(BuildContext context) => Padding(
      padding: const EdgeInsets.fromLTRB(20, 20, 12, 14),
      child: Row(children: [
        Expanded(
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title,
              style: Theme.of(context)
                  .textTheme
                  .headlineMedium
                  ?.copyWith(fontWeight: FontWeight.bold)),
          if (subtitle != null)
            Text(subtitle!, style: const TextStyle(color: Colors.white54))
        ])),
        if (action != null) action!
      ]));
}

class _Metric extends StatelessWidget {
  const _Metric(this.label, this.value);
  final String label;
  final Object value;
  @override
  Widget build(BuildContext context) => Column(children: [
        Text('$value',
            style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.bold, color: const Color(0xff75a7ff))),
        Text(label, style: const TextStyle(color: Colors.white54))
      ]);
}

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final watchlists = ref.watch(watchlistsProvider),
        momentum = ref.watch(detectionProvider('MOMENTUM'));
    ref.listen(marketEventProvider, (previous, next) {
      next.whenData((event) {
        if (event.type.contains('detected')) {
          ref.invalidate(detectionProvider('MOMENTUM'));
        }
      });
    });
    return CustomScrollView(slivers: [
      SliverToBoxAdapter(
          child: PageHeader('오늘의 시장',
              subtitle: '관심 종목과 Momentum을 확인하세요.',
              action: IconButton(
                  onPressed: () => context.push('/search'),
                  icon: const Icon(Icons.search)))),
      SliverToBoxAdapter(
          child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Card(
                  child: Padding(
                      padding: const EdgeInsets.all(18),
                      child: Row(
                          mainAxisAlignment: MainAxisAlignment.spaceAround,
                          children: [
                            _Metric(
                                '관심 종목',
                                watchlists.asData?.value.fold<int>(
                                        0, (sum, g) => sum + g.items.length) ??
                                    0),
                            _Metric(
                                'Momentum', momentum.asData?.value.length ?? 0),
                            const _Metric('연결', 'LIVE')
                          ]))))),
      SliverToBoxAdapter(
          child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 24, 20, 10),
              child: Text('Momentum Top',
                  style: Theme.of(context).textTheme.titleLarge))),
      SliverToBoxAdapter(
          child: SizedBox(
              height: 280,
              child: AsyncPane(
                  value: momentum,
                  data: (items) => items.isEmpty
                      ? const Center(
                          child: Text('아직 실시간 탐지가 없습니다',
                              style: TextStyle(color: Colors.white54)))
                      : ListView.separated(
                          padding: const EdgeInsets.symmetric(horizontal: 16),
                          itemCount: items.take(5).length,
                          separatorBuilder: (_, __) =>
                              const SizedBox(height: 8),
                          itemBuilder: (_, i) => DetectionTile(items[i])))))
    ]);
  }
}

class WatchlistScreen extends ConsumerWidget {
  const WatchlistScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Column(children: [
        PageHeader('관심종목',
            subtitle: '그룹별로 종목을 관리하세요.',
            action: IconButton(
                icon: const Icon(Icons.add),
                onPressed: () => _createGroup(context, ref))),
        Expanded(
            child: AsyncPane(
                value: ref.watch(watchlistsProvider),
                data: (groups) => groups.isEmpty
                    ? const Center(child: Text('첫 관심종목 그룹을 만들어보세요'))
                    : RefreshIndicator(
                        onRefresh: () => ref.refresh(watchlistsProvider.future),
                        child: ListView.builder(
                            padding: const EdgeInsets.all(16),
                            itemCount: groups.length,
                            itemBuilder: (_, index) {
                              final group = groups[index];
                              return Card(
                                  child: ExpansionTile(
                                      initiallyExpanded: true,
                                      title: Text(group.name),
                                      subtitle:
                                          Text('${group.items.length}개 종목'),
                                      children: group.items
                                          .map((item) => ListTile(
                                              onTap: () =>
                                                  openStock(context, item),
                                              leading: CircleAvatar(
                                                  child: Text(item.name[0])),
                                              title: Text(item.name),
                                              subtitle: Text(
                                                  '${item.code} · ${item.market}'),
                                              trailing: IconButton(
                                                  icon: const Icon(Icons.close),
                                                  onPressed: () async {
                                                    await ref
                                                        .read(
                                                            repositoryProvider)
                                                        .removeWatchlist(
                                                            item.id);
                                                    ref.invalidate(
                                                        watchlistsProvider);
                                                  })))
                                          .toList()));
                            }))))
      ]);
  Future<void> _createGroup(BuildContext context, WidgetRef ref) async {
    final controller = TextEditingController();
    final name = await showDialog<String>(
        context: context,
        builder: (context) => AlertDialog(
                title: const Text('새 그룹'),
                content: TextField(
                    controller: controller,
                    autofocus: true,
                    decoration: const InputDecoration(labelText: '그룹 이름')),
                actions: [
                  TextButton(
                      onPressed: () => context.pop(), child: const Text('취소')),
                  FilledButton(
                      onPressed: () => context.pop(controller.text),
                      child: const Text('추가'))
                ]));
    if (name != null && name.trim().isNotEmpty) {
      await ref.read(repositoryProvider).createGroup(name.trim());
      ref.invalidate(watchlistsProvider);
    }
  }
}

class DetectionScreen extends ConsumerWidget {
  const DetectionScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Column(children: [
        const PageHeader('실시간 탐지', subtitle: 'Momentum 최초 진입 이벤트'),
        Expanded(
            child: AsyncPane(
                value: ref.watch(detectionProvider('MOMENTUM')),
                data: (items) => RefreshIndicator(
                    onRefresh: () =>
                        ref.refresh(detectionProvider('MOMENTUM').future),
                    child: items.isEmpty
                        ? ListView(children: const [
                            Padding(
                                padding: EdgeInsets.only(top: 180),
                                child: Center(child: Text('포착된 종목이 없습니다')))
                          ])
                        : ListView.separated(
                            padding: const EdgeInsets.all(16),
                            itemCount: items.length,
                            separatorBuilder: (_, __) =>
                                const SizedBox(height: 8),
                            itemBuilder: (_, i) => DetectionTile(items[i])))))
      ]);
}

class DetectionTile extends StatelessWidget {
  const DetectionTile(this.item, {super.key});
  final Detection item;
  @override
  Widget build(BuildContext context) => Card(
      child: ListTile(
          onTap: () => openStock(context, item.stock),
          leading: CircleAvatar(child: Text(item.stock.name[0])),
          title: Text(item.stock.name),
          subtitle: Text(
              '${item.type} · 거래량 ${item.volumeRatio.toStringAsFixed(2)}×'),
          trailing: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(money(item.price)),
                Text(
                    '${item.changeRate >= 0 ? '+' : ''}${item.changeRate.toStringAsFixed(2)}%',
                    style: TextStyle(
                        color: item.changeRate >= 0
                            ? Colors.redAccent
                            : Colors.blueAccent))
              ])));
}

class ScannerScreen extends ConsumerStatefulWidget {
  const ScannerScreen({super.key});
  @override
  ConsumerState<ScannerScreen> createState() => _ScannerScreenState();
}

class _ScannerScreenState extends ConsumerState<ScannerScreen> {
  String type = 'VOLUME';
  @override
  Widget build(BuildContext context) => Column(children: [
        const PageHeader('시장 Scanner', subtitle: '5분봉 가격·거래량 조건'),
        Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'VOLUME', label: Text('거래량')),
                  ButtonSegment(value: 'PRICE_RISE', label: Text('급등')),
                  ButtonSegment(value: 'MOMENTUM', label: Text('Momentum'))
                ],
                selected: {
                  type
                },
                onSelectionChanged: (value) =>
                    setState(() => type = value.first))),
        Expanded(
            child: AsyncPane(
                value: ref.watch(detectionProvider(type)),
                data: (items) => items.isEmpty
                    ? const Center(child: Text('현재 조건을 통과한 종목이 없습니다'))
                    : ListView.separated(
                        padding: const EdgeInsets.all(16),
                        itemCount: items.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 8),
                        itemBuilder: (_, i) => DetectionTile(items[i]))))
      ]);
}

class JournalScreen extends ConsumerWidget {
  const JournalScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
      backgroundColor: Colors.transparent,
      floatingActionButton: FloatingActionButton.extended(
          onPressed: () => _newTrade(context, ref),
          icon: const Icon(Icons.add),
          label: const Text('거래 기록')),
      body: Column(children: [
        const PageHeader('투자 노트', subtitle: '실제 주문이 아닌 개인 투자기록입니다.'),
        Expanded(
            child: AsyncPane(
                value: ref.watch(tradesProvider),
                data: (items) => items.isEmpty
                    ? const Center(child: Text('첫 매매 기록을 남겨보세요'))
                    : ListView.separated(
                        padding: const EdgeInsets.all(16),
                        itemCount: items.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 8),
                        itemBuilder: (_, i) {
                          final t = items[i];
                          return Card(
                              child: ListTile(
                                  leading: Chip(
                                      label:
                                          Text(t.type == 'BUY' ? '매수' : '매도')),
                                  title: Text(t.stock.name),
                                  subtitle: Text(
                                      '${t.quantity}주 · ${money(t.price)}'),
                                  trailing: Text(money(t.amount))));
                        })))
      ]));
  Future<void> _newTrade(BuildContext context, WidgetRef ref) async {
    final code = TextEditingController(),
        price = TextEditingController(),
        quantity = TextEditingController();
    var type = 'BUY';
    final saved = await showDialog<bool>(
        context: context,
        builder: (context) => StatefulBuilder(
            builder: (context, setState) => AlertDialog(
                    title: const Text('거래 기록'),
                    content: SingleChildScrollView(
                        child:
                            Column(mainAxisSize: MainAxisSize.min, children: [
                      SegmentedButton<String>(
                          segments: const [
                            ButtonSegment(value: 'BUY', label: Text('매수')),
                            ButtonSegment(value: 'SELL', label: Text('매도'))
                          ],
                          selected: {
                            type
                          },
                          onSelectionChanged: (v) =>
                              setState(() => type = v.first)),
                      TextField(
                          controller: code,
                          decoration: const InputDecoration(labelText: '종목코드')),
                      TextField(
                          controller: price,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(labelText: '가격')),
                      TextField(
                          controller: quantity,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(labelText: '수량'))
                    ])),
                    actions: [
                      TextButton(
                          onPressed: () => context.pop(false),
                          child: const Text('취소')),
                      FilledButton(
                          onPressed: () async {
                            await ref.read(repositoryProvider).createTrade(
                                code: code.text.toUpperCase(),
                                type: type,
                                price: num.parse(price.text),
                                quantity: int.parse(quantity.text));
                            if (context.mounted) context.pop(true);
                          },
                          child: const Text('저장'))
                    ])));
    if (saved == true) ref.invalidate(tradesProvider);
  }
}

class StockSearchScreen extends ConsumerStatefulWidget {
  const StockSearchScreen({super.key});
  @override
  ConsumerState<StockSearchScreen> createState() => _StockSearchScreenState();
}

class _StockSearchScreenState extends ConsumerState<StockSearchScreen> {
  String query = '';
  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(
          title: TextField(
              autofocus: true,
              onSubmitted: (value) => setState(() => query = value.trim()),
              decoration: const InputDecoration(
                  hintText: '종목명 또는 코드', prefixIcon: Icon(Icons.search)))),
      body: AsyncPane(
          value: ref.watch(searchProvider(query)),
          data: (items) => query.isEmpty
              ? const Center(child: Text('검색어를 입력하세요'))
              : items.isEmpty
                  ? const Center(child: Text('검색 결과가 없습니다'))
                  : ListView.builder(
                      itemCount: items.length,
                      itemBuilder: (_, i) {
                        final stock = items[i];
                        return ListTile(
                            onTap: () => openStock(context, stock),
                            title: Text(stock.name),
                            subtitle: Text('${stock.code} · ${stock.market}'),
                            trailing: const Icon(Icons.chevron_right));
                      })));
}

class StockDetailScreen extends ConsumerWidget {
  const StockDetailScreen(
      {required this.code,
      required this.name,
      required this.market,
      super.key});
  final String code, name, market;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final quote = ref.watch(quoteProvider(code));
    return Scaffold(
        appBar: AppBar(title: Text(name), actions: [
          IconButton(
              onPressed: () => _add(context, ref),
              icon: const Icon(Icons.star_border))
        ]),
        body: AsyncPane(
            value: quote,
            data: (q) => RefreshIndicator(
                onRefresh: () => ref.refresh(quoteProvider(code).future),
                child: ListView(padding: const EdgeInsets.all(20), children: [
                  Text('$market · $code',
                      style: const TextStyle(color: Color(0xff75a7ff))),
                  const SizedBox(height: 8),
                  Text(name,
                      style: Theme.of(context)
                          .textTheme
                          .headlineMedium
                          ?.copyWith(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 28),
                  Text(money(q.price),
                      style: Theme.of(context)
                          .textTheme
                          .displaySmall
                          ?.copyWith(fontWeight: FontWeight.bold)),
                  Text(
                      '${q.change >= 0 ? '▲' : '▼'} ${money(q.change.abs())} (${q.changeRate.abs().toStringAsFixed(2)}%)',
                      style: TextStyle(
                          color: q.change >= 0
                              ? Colors.redAccent
                              : Colors.blueAccent)),
                  const SizedBox(height: 30),
                  Card(
                      child: Padding(
                          padding: const EdgeInsets.all(18),
                          child: Column(children: [
                            _row('시가', money(q.open)),
                            _row('고가', money(q.high)),
                            _row('저가', money(q.low)),
                            _row('누적 거래량', q.volume.toInt().toString())
                          ])))
                ]))));
  }

  Widget _row(String label, String value) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
        Text(label, style: const TextStyle(color: Colors.white54)),
        Text(value, style: const TextStyle(fontWeight: FontWeight.bold))
      ]));
  Future<void> _add(BuildContext context, WidgetRef ref) async {
    final groups = await ref.read(watchlistsProvider.future);
    if (!context.mounted) return;
    if (groups.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('먼저 관심종목 그룹을 만들어주세요.')));
      return;
    }
    final group = await showModalBottomSheet<WatchlistGroup>(
        context: context,
        builder: (context) => ListView(shrinkWrap: true, children: [
              const ListTile(title: Text('추가할 그룹 선택')),
              for (final group in groups)
                ListTile(
                    title: Text(group.name), onTap: () => context.pop(group))
            ]));
    if (group != null) {
      await ref.read(repositoryProvider).addWatchlist(group.id, code);
      ref.invalidate(watchlistsProvider);
    }
  }
}

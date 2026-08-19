num _number(dynamic value) => value is num ? value : num.tryParse('$value') ?? 0;

class Stock {
  const Stock({required this.code, required this.name, required this.market});
  final String code;
  final String name;
  final String market;
  factory Stock.fromJson(Map<String, dynamic> json) => Stock(
        code: '${json['stockCode']}',
        name: '${json['stockName']}',
        market: '${json['market']}',
      );
}

class Quote {
  const Quote({required this.price, required this.change, required this.changeRate,
    required this.open, required this.high, required this.low, required this.volume});
  final num price, change, changeRate, open, high, low, volume;
  factory Quote.fromJson(Map<String, dynamic> json) => Quote(
    price: _number(json['currentPrice']), change: _number(json['change']),
    changeRate: _number(json['changeRate']), open: _number(json['openPrice']),
    high: _number(json['highPrice']), low: _number(json['lowPrice']),
    volume: _number(json['accumulatedVolume']),
  );
}

class WatchlistGroup {
  const WatchlistGroup({required this.id, required this.name, required this.items});
  final int id;
  final String name;
  final List<WatchlistItem> items;
  factory WatchlistGroup.fromJson(Map<String, dynamic> json) => WatchlistGroup(
    id: _number(json['id']).toInt(), name: '${json['name']}',
    items: ((json['items'] as List?) ?? []).map((e) => WatchlistItem.fromJson((e as Map).cast())).toList(),
  );
}

class WatchlistItem extends Stock {
  const WatchlistItem({required this.id, required super.code, required super.name, required super.market});
  final int id;
  factory WatchlistItem.fromJson(Map<String, dynamic> json) => WatchlistItem(
    id: _number(json['id']).toInt(), code: '${json['stockCode']}',
    name: '${json['stockName']}', market: '${json['market']}',
  );
}

class Detection {
  const Detection({required this.id, required this.type, required this.stock,
    required this.detectedAt, required this.price, required this.changeRate,
    required this.volumeRatio, required this.score});
  final int id;
  final String type;
  final Stock stock;
  final DateTime detectedAt;
  final num price, changeRate, volumeRatio, score;
  factory Detection.fromJson(Map<String, dynamic> json) => Detection(
    id: _number(json['id']).toInt(), type: '${json['scannerType']}',
    stock: Stock(code: '${json['stockCode']}', name: '${json['stockName']}', market: '${json['market']}'),
    detectedAt: DateTime.parse('${json['detectedAt']}'), price: _number(json['detectedPrice']),
    changeRate: _number(json['fiveMinuteChangeRate']), volumeRatio: _number(json['volumeRatio']),
    score: _number(json['momentumScore']),
  );
}

class TradeRecord {
  const TradeRecord({required this.id, required this.stock, required this.type,
    required this.tradedAt, required this.price, required this.quantity,
    required this.amount, required this.realizedPnl});
  final int id;
  final Stock stock;
  final String type;
  final DateTime tradedAt;
  final num price, quantity, amount, realizedPnl;
  factory TradeRecord.fromJson(Map<String, dynamic> json) => TradeRecord(
    id: _number(json['id']).toInt(), stock: Stock(code: '${json['stockCode']}', name: '${json['stockName']}', market: '${json['market']}'),
    type: '${json['tradeType']}', tradedAt: DateTime.parse('${json['tradedAt']}'),
    price: _number(json['price']), quantity: _number(json['quantity']),
    amount: _number(json['amount']), realizedPnl: _number(json['realizedPnl']),
  );
}

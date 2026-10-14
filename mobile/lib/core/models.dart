// 서버 JSON 응답을 앱에서 쓰는 Dart 객체(모델)로 바꾸는 파일이에요.
// 종목, 시세, 관심종목 그룹, 탐지 결과, 수동 거래 기록 모델을 정의해요.
// repositories.dart가 응답을 변환할 때, 화면들이 값을 보여 줄 때 사용해요.

/// 숫자 또는 숫자 문자열을 num으로 바꿔요. 받는 것: 아무 값. 돌려주는 것: 숫자(해석 실패나 null이면 0).
/// ⚠️ 확인 필요: 값이 없을 때도 0이 되어, '0원'과 '데이터 없음'을 화면에서 구분할 수 없어요.
num _number(dynamic value) => value is num ? value : num.tryParse('$value') ?? 0;

/// 종목 기본 정보예요. 명함처럼 코드·이름·시장(KOSPI 등)만 담아요.
class Stock {
  const Stock({required this.code, required this.name, required this.market});
  final String code;
  final String name;
  final String market;
  /// 서버의 stockCode·stockName·market 필드를 읽어 Stock을 만들어요.
  factory Stock.fromJson(Map<String, dynamic> json) => Stock(
        code: '${json['stockCode']}',
        name: '${json['stockName']}',
        market: '${json['market']}',
      );
}

/// 한 종목의 현재 시세예요. 전광판 한 줄처럼 현재가·등락·시고저·누적 거래량을 담아요.
class Quote {
  const Quote({required this.price, required this.change, required this.changeRate,
    required this.open, required this.high, required this.low, required this.volume});
  final num price, change, changeRate, open, high, low, volume;
  /// 서버 시세 JSON(currentPrice, openPrice 등)을 읽어 Quote를 만들어요.
  factory Quote.fromJson(Map<String, dynamic> json) => Quote(
    price: _number(json['currentPrice']), change: _number(json['change']),
    changeRate: _number(json['changeRate']), open: _number(json['openPrice']),
    high: _number(json['highPrice']), low: _number(json['lowPrice']),
    volume: _number(json['accumulatedVolume']),
  );
}

/// 관심종목 그룹이에요. 폴더처럼 이름과 그 안의 종목 목록(items)을 가져요.
class WatchlistGroup {
  const WatchlistGroup({required this.id, required this.name, required this.items});
  final int id;
  final String name;
  final List<WatchlistItem> items;
  /// 서버 JSON을 읽어 그룹과 그 안의 항목들을 함께 만들어요. items가 없으면 빈 목록이에요.
  factory WatchlistGroup.fromJson(Map<String, dynamic> json) => WatchlistGroup(
    id: _number(json['id']).toInt(), name: '${json['name']}',
    items: ((json['items'] as List?) ?? []).map((e) => WatchlistItem.fromJson((e as Map).cast())).toList(),
  );
}

/// 관심종목 그룹 안의 한 항목이에요. Stock 정보에 삭제할 때 쓰는 항목 id를 더했어요.
class WatchlistItem extends Stock {
  const WatchlistItem({required this.id, required super.code, required super.name, required super.market});
  final int id;
  /// 서버 JSON을 읽어 WatchlistItem을 만들어요.
  factory WatchlistItem.fromJson(Map<String, dynamic> json) => WatchlistItem(
    id: _number(json['id']).toInt(), code: '${json['stockCode']}',
    name: '${json['stockName']}', market: '${json['market']}',
  );
}

/// Scanner가 조건을 만족한 종목을 포착한 기록 한 건이에요. 과속 단속 카메라 사진처럼 언제·얼마에·어떤 조건으로 잡혔는지 담아요.
class Detection {
  const Detection({required this.id, required this.type, required this.stock,
    required this.detectedAt, required this.price, required this.changeRate,
    required this.volumeRatio, required this.score});
  final int id;
  final String type;
  final Stock stock;
  final DateTime detectedAt;
  final num price, changeRate, volumeRatio, score;
  /// 서버 탐지 JSON을 읽어 Detection을 만들어요.
  /// changeRate는 5분 등락률(fiveMinuteChangeRate), score는 모멘텀 점수(momentumScore)예요. 상승 확률이 아니에요.
  factory Detection.fromJson(Map<String, dynamic> json) => Detection(
    id: _number(json['id']).toInt(), type: '${json['scannerType']}',
    stock: Stock(code: '${json['stockCode']}', name: '${json['stockName']}', market: '${json['market']}'),
    detectedAt: DateTime.parse('${json['detectedAt']}'), price: _number(json['detectedPrice']),
    changeRate: _number(json['fiveMinuteChangeRate']), volumeRatio: _number(json['volumeRatio']),
    score: _number(json['momentumScore']),
  );
}

/// 사용자가 직접 남긴 매매 기록 한 건이에요. 가계부 한 줄처럼 실제 주문이 아닌 수동 기록이에요.
class TradeRecord {
  const TradeRecord({required this.id, required this.stock, required this.type,
    required this.tradedAt, required this.price, required this.quantity,
    required this.amount, required this.realizedPnl});
  final int id;
  final Stock stock;
  final String type;
  final DateTime tradedAt;
  final num price, quantity, amount, realizedPnl;
  /// 서버 거래 기록 JSON을 읽어 TradeRecord를 만들어요.
  factory TradeRecord.fromJson(Map<String, dynamic> json) => TradeRecord(
    id: _number(json['id']).toInt(), stock: Stock(code: '${json['stockCode']}', name: '${json['stockName']}', market: '${json['market']}'),
    type: '${json['tradeType']}', tradedAt: DateTime.parse('${json['tradedAt']}'),
    price: _number(json['price']), quantity: _number(json['quantity']),
    amount: _number(json['amount']), realizedPnl: _number(json['realizedPnl']),
  );
}

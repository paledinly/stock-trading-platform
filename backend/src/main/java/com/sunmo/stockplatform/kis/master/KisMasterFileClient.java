/*
 * KIS가 공개하는 종목 마스터 파일(시장별 전체 종목 목록이 담긴 zip)을 내려받아 압축을 푸는 파일이에요.
 * 압축 안에서 기대한 파일 이름(kospi_code.mst 또는 kosdaq_code.mst)만 골라 바이트로 돌려줘요.
 * StockMasterSyncService가 종목 마스터를 동기화할 때 이 클라이언트로 원본 파일을 가져와요.
 */
package com.sunmo.stockplatform.kis.master;

import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.stock.domain.Market;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.zip.ZipInputStream;

/**
 * 시장별 종목 마스터 zip을 받아 안의 .mst 파일 내용만 꺼내 주는 클라이언트예요.
 * 택배 상자를 받아 열고, 주문한 물건 하나만 꺼내 건네주는 수령 담당과 같아요.
 */
@Component
public class KisMasterFileClient {
    private final RestClient restClient;
    private final KisProperties properties;

    /**
     * 받는 것: KIS용 RestClient, KIS 설정(마스터 파일 주소 포함). 돌려주는 것: 없음(생성자).
     */
    public KisMasterFileClient(RestClient kisRestClient, KisProperties properties) {
        this.restClient = kisRestClient;
        this.properties = properties;
    }

    /**
     * 시장에 맞는 마스터 zip을 내려받아 .mst 파일 내용을 꺼내요.
     * 받는 것: 시장(KOSPI 또는 그 외는 KOSDAQ). 돌려주는 것: .mst 파일의 원본 바이트.
     */
    public byte[] download(Market market) {
        // 준비: 시장별 주소와 압축 안에서 찾을 파일 이름을 정해요.
        URI uri = market == Market.KOSPI ? properties.master().kospiUrl() : properties.master().kosdaqUrl();
        String expected = market == Market.KOSPI ? "kospi_code.mst" : "kosdaq_code.mst";
        try {
            byte[] zip = restClient.get().uri(uri).retrieve().body(byte[].class);
            if (zip == null || zip.length == 0)
                throw failure("KIS master download returned empty data", null);
            return extract(zip, expected);
        } catch (RestClientException exception) {
            throw failure("KIS master download failed for " + market, exception);
        }
    }

    /**
     * zip 바이트에서 기대한 이름으로 끝나는 파일 하나를 찾아 내용을 꺼내요.
     * ".."이 들어간 경로는 건너뛰어 압축 경로 조작을 막아요.
     * 받는 것: zip 바이트, 찾을 파일 이름. 돌려주는 것: 그 파일의 바이트, 없으면 동기화 실패 예외.
     */
    byte[] extract(byte[] zip, String expectedFileName) {
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                String name = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && !name.contains("..") && name.endsWith(expectedFileName)) {
                    var output = new ByteArrayOutputStream();
                    input.transferTo(output);
                    return output.toByteArray();
                }
            }
            throw failure("Expected master file is missing from archive: " + expectedFileName, null);
        } catch (IOException exception) {
            throw failure("Cannot extract KIS master archive", exception);
        }
    }

    /**
     * 종목 마스터 동기화 실패 예외를 만들어요.
     * 받는 것: 설명 문구, 원인 예외(없으면 null). 돌려주는 것: STOCK_MASTER_SYNC_FAILED, HTTP 502 예외.
     */
    private ApplicationException failure(String message, Throwable cause) {
        return new ApplicationException(ErrorCode.STOCK_MASTER_SYNC_FAILED, HttpStatus.BAD_GATEWAY, message, cause);
    }
}

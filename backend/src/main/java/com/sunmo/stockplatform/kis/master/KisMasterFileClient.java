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

@Component
public class KisMasterFileClient {
    private final RestClient restClient;
    private final KisProperties properties;

    public KisMasterFileClient(RestClient kisRestClient, KisProperties properties) {
        this.restClient = kisRestClient;
        this.properties = properties;
    }

    public byte[] download(Market market) {
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

    private ApplicationException failure(String message, Throwable cause) {
        return new ApplicationException(ErrorCode.STOCK_MASTER_SYNC_FAILED, HttpStatus.BAD_GATEWAY, message, cause);
    }
}

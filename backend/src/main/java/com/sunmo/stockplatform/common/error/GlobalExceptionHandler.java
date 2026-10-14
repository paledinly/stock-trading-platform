/*
 * 컨트롤러에서 빠져나온 예외를 한곳에서 받아 표준 오류 응답(ProblemDetail, RFC 7807 형식)으로 바꾸는 파일이에요.
 * 응답에는 상태 코드, 메시지, 그리고 "code" 속성으로 ErrorCode 이름이 들어가요.
 * 모든 REST 컨트롤러(api 패키지들)에 자동으로 적용돼요.
 */
package com.sunmo.stockplatform.common.error;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 모든 REST(주소와 HTTP 메서드로 데이터를 주고받는 웹 API 방식) 컨트롤러의 예외를 대신 처리하는 클래스예요.
 * 고객센터의 민원 창구처럼, 어느 부서에서 생긴 문제든 여기로 모아 정해진 양식의 답장으로 돌려줘요.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 프로젝트 공통 예외를 오류 응답으로 바꿔요.
     * 받는 것: ApplicationException. 돌려주는 것: 예외에 담긴 HTTP 상태, 메시지, 오류 코드를 채운 ProblemDetail.
     */
    @ExceptionHandler(ApplicationException.class)
    ProblemDetail handleApplicationException(ApplicationException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        detail.setTitle(exception.errorCode().name());
        detail.setProperty("code", exception.errorCode().name());
        return detail;
    }

    /**
     * 입력값 검증(@Valid, @Pattern 등) 실패를 400 Bad Request 응답으로 바꿔요.
     * 받는 것: 검증 예외. 돌려주는 것: INVALID_REQUEST 코드를 담은 ProblemDetail.
     */
    @ExceptionHandler({ ConstraintViolationException.class, MethodArgumentNotValidException.class })
    ProblemDetail handleValidation(Exception exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle(ErrorCode.INVALID_REQUEST.name());
        detail.setProperty("code", ErrorCode.INVALID_REQUEST.name());
        return detail;
    }
}

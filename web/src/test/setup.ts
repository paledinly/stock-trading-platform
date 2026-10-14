/**
 * 모든 테스트가 실행되기 전에 한 번 읽히는 준비 파일이에요.
 * toBeInTheDocument 같은 화면 검사용 expect 확장 기능을 등록해요.
 * vite.config.ts의 test.setupFiles가 이 파일을 지정해요.
 */
import '@testing-library/jest-dom'


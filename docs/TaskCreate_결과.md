  # 문서목적
  - plan mode로 정리한 프로그램 명세, 각종 정의서를 바탕으로 taskcreate툴로 만든 구현 순서 정리, 이 순서대로 claude code에게 구현 의뢰함
  
  1. DB 프로필/데이터소스 설정
  2. TB_NOTIFY_HISTORY DDL+시퀀스
  3. NotifyConstants
  4. 통보 스케쥴 VO+파서+테스트
  5. 수신자 파일 파서+테스트
  6. TR 바이트 인코더+테스트
  7. AnalyzeResultMapper(읽기+NOTIFY_YN갱신)
  8. NotifyHistoryMapper
  9. SMS TR 소켓 클라이언트+통합테스트
  10. NotifyAggregationService+테스트
  11. NotifyService(오케스트레이션, crash-safe 처리)+테스트
  12. NotifyScheduler
  13. 전체 빌드/테스트

  의존관계도 걸어둠(7,8은 1·2 끝나야, 9는 3·6, 10은 7·8, 11은 9·10, 12는 4·11, 13은 5·12)
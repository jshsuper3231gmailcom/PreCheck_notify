# PreCheck 통보 TR 연동 정의서 v1.0

---

## 1. 문서 목적

이 문서는 통보 서버가 레거시 SMS 게이트웨이와 통신할 때 사용하는 TR(Bind & Submit, TCP 소켓 기반) 바이트 규격을 정의한다. 원본 규격은 레거시 시스템 화면의 C 구조체 정의(`BISUB_HEADER`, `SMSSUBMIT_BODY`)를 캡처해 확인했으며, 그 캡처에는 요청(bind/submit) 구조체만 있고 응답(ack) 구조체는 포함되어 있지 않았다.

DB 테이블(`TB_NOTIFY_HISTORY`) 구조는 `통보_DB정의서.md`에서, 통보 스케쥴 conf 파일 포맷은 별도 통보 스케쥴러 정의서에서 다루며, 이 문서는 TR 바이트 레이아웃과 필드 채움값만 다룬다.

---

## 2. 공통 사항

| 항목 | 내용 |
|---|---|
| 연결 방식 | TCP, 비연결형 (스케쥴 실행마다 connection을 새로 열어 bind 1회 + submit N회 처리 후 닫음, connection을 계속 유지하지 않음) |
| 인코딩 | EUC-KR |
| 패딩 문자 | 빈 필드/수치형 빈 자리는 ASCII 스페이스(0x20)로 채움 |
| 응답(ack) | 없음. 통보 서버는 TCP write 성공 여부로만 성공/실패를 판단함 (fire-and-forget) |
| TR 1건 전체 길이 | `BISUB_HEADER`(5바이트) + `SMSSUBMIT_BODY`(353바이트) = **358바이트** |

---

## 3. BISUB_HEADER (5바이트)

```c
typedef struct
{
    char sms_code[2];      /* sms code 01-bind 03-submit(SMS) 04-submit(알림톡) */
    char body_length[3];   /* body 길이 */
} BISUB_HEADER;  /* Bind & Submit 헤더 */
```

| 필드 | 길이 | 설명 | 통보 서버 채움값 |
|---|---|---|---|
| `sms_code` | 2 | TR 종류: `01`=bind, `03`=submit(SMS), `04`=submit(알림톡) | `01`(bind) 또는 `03`(submit). `04`(알림톡)는 사용하지 않음 |
| `body_length` | 3 | 뒤따르는 `SMSSUBMIT_BODY`의 길이(바이트) | 고정값 `"353"` |

---

## 4. SMSSUBMIT_BODY (353바이트)

```c
typedef struct
{
    char blank[16];        /* 16자리 공백 */
    char sms_code[2];      /* 01(개설내용통보), 02(개별발송), 03(캠페인발송) */
    char req_date[14];     /* sms 요청일시간 */
    char seq_no[8];        /* sms 요청 고유 번호 */
    char sender_info[15];  /* 발신 정보, 발신 서버 IP 또는 발신자 전화번호 */
    char branch_code[5];   /* 지점코드 */
    char term_ip[15];      /* 단말기 IP */
    char recv_phn[15];     /* 수신전화번호, 고객휴대폰 번호 */
    char recv_phn_snd[15]; /* 수신전화번호 2, 고객휴대폰번호 2 */
    char message[80];      /* 보낼 메시지 */
    char accnt_no[12];     /* 계좌번호 */
    char junin_id[13];     /* 주민번호 */
    char receiver_nm[20];  /* 수신자 성명 */
    char che_num[8];       /* 체결번호 */
    char order_no[6];      /* 주문번호 */
    char origin_no[6];     /* 원주문번호 */
    char che_date[14];     /* 체결일자 */
    char accnt_admin_id[10]; /* 계좌관리자 사번 */
    char admin_id[10];     /* 관리자 사번 */
    char send_phn[15];     /* 발송전화 */
    char recall_phn[15];   /* 회신전화 */
    char blank2[14];       /* blank */
    char filler[24];       /* filler */
    char end[1];           /* 종료 0x03 */
} SMSSUBMIT_BODY; /* Submit Body */
```

| 순번 | 필드 | 길이 | 설명 | 통보 서버 채움값 |
|---|---|---|---|---|
| 1 | `blank` | 16 | 16자리 공백 | 스페이스(0x20) 16개 |
| 2 | `sms_code` | 2 | `01`=개설내용통보, `02`=개별발송, `03`=캠페인발송 | 고정값 `"02"` (개별발송) |
| 3 | `req_date` | 14 | SMS 요청 일시 (`yyyyMMddHHmmss`) | TR 전송 시각 |
| 4 | `seq_no` | 8 | SMS 요청 고유 번호 | `SEQ_NOTIFY_TR_SEQNO.NEXTVAL`을 8자리 0-padding (99999999 초과 시 1로 순환) |
| 5 | `sender_info` | 15 | 발신 정보 (발신 서버 IP 또는 발신자 전화번호) | 고정값 `"130.2.4.12"` (환경 무관) |
| 6 | `branch_code` | 5 | 지점코드 | blank (스페이스 5개) |
| 7 | `term_ip` | 15 | 단말기 IP | 고정값 `"130.2.4.12"` (환경 무관) |
| 8 | `recv_phn` | 15 | 수신전화번호 (고객휴대폰 번호) | 전송대상 파일에서 읽은 전화번호 1건 |
| 9 | `recv_phn_snd` | 15 | 수신전화번호 2 | 사용 안 함, blank |
| 10 | `message` | 80 | 보낼 메시지 | `"[서버명] 정상N 경고N 에러N (HH:mm~HH:mm)"`, EUC-KR 인코딩 후 80바이트로 truncate |
| 11 | `accnt_no` | 12 | 계좌번호 | blank (금융 전용 필드, 통보 서버 미사용) |
| 12 | `junin_id` | 13 | 주민번호 | blank (금융 전용 필드, 통보 서버 미사용) |
| 13 | `receiver_nm` | 20 | 수신자 성명 | blank (전송대상 파일에 이름 컬럼 없음) |
| 14 | `che_num` | 8 | 체결번호 | blank (금융 전용 필드, 통보 서버 미사용) |
| 15 | `order_no` | 6 | 주문번호 | blank (금융 전용 필드, 통보 서버 미사용) |
| 16 | `origin_no` | 6 | 원주문번호 | blank (금융 전용 필드, 통보 서버 미사용) |
| 17 | `che_date` | 14 | 체결일자 | blank (금융 전용 필드, 통보 서버 미사용) |
| 18 | `accnt_admin_id` | 10 | 계좌관리자 사번 | blank (금융 전용 필드, 통보 서버 미사용) |
| 19 | `admin_id` | 10 | 관리자 사번 | blank (금융 전용 필드, 통보 서버 미사용) |
| 20 | `send_phn` | 15 | 발송전화 | blank (금융 전용 필드, 통보 서버 미사용) |
| 21 | `recall_phn` | 15 | 회신전화 | blank (금융 전용 필드, 통보 서버 미사용) |
| 22 | `blank2` | 14 | blank | 스페이스(0x20) 14개 |
| 23 | `filler` | 24 | filler | 스페이스(0x20) 24개 |
| 24 | `end` | 1 | 종료 마커 | 고정값 `0x03` |

> 필드 길이 합계: 16+2+14+8+15+5+15+15+15+80+12+13+20+8+6+6+14+10+10+15+15+14+24+1 = **353바이트** (`BISUB_HEADER.body_length`와 일치)

---

## 5. 통보 서버 처리 절차 (연결 ~ 종료)

connection은 **스케쥴 1회 실행 전체에서 1개만** 연다(서버별로 따로 열지 않음). 그 안에서 통보 대상 서버를 순서대로 한 서버씩 처리하며, **서버 하나를 끝낼 때마다 즉시 그 서버의 `TB_NOTIFY_HISTORY`를 INSERT한다** (전체 서버를 다 처리한 뒤 한꺼번에 기록하지 않음 — crash-safe).

```
① 통보 스케쥴 도래, 통보 대상 서버 목록 확정(경고/에러 1건 이상인 서버들)
        ↓
② TCP connection 1회 open (SMS 게이트웨이 host:port, 설정값)
        ↓
③ BISUB_HEADER(sms_code="01", body_length="000")만 전송, 바디 없음(헤더 5바이트만)
   ※ bind 전용 바디 구조는 원본 화면 캡처에 별도로 제시되지 않아(6절), 구현상 "바디 없음"으로 가정함
     (SMSSUBMIT_BODY.sms_code 값 01/02/03은 전부 실제 SMS 카테고리값이라 bind 의미와 겹치지 않음 — 운영 연동 전 재확인 필요)
   - bind 자체가 실패하면 → 통보 대상 서버 전원 FAIL(0건 시도)로 즉시 기록, 런 종료 (④로 진행 안 함)
        ↓
④ 통보 대상 서버를 한 서버씩 순회:
     해당 서버의 수신자 전화번호마다 BISUB_HEADER(sms_code="03") + SMSSUBMIT_BODY(4절 채움값) 전송
     → 이 서버의 수신자 전원 submit 성공 시: NOTIFY_STATUS='SUCCESS'
     → 이 서버 처리 중 connection이 끊기면: 그때까지 성공한 건수 기준 NOTIFY_STATUS='PARTIAL'(1건 이상 성공) 또는 'FAIL'(0건),
       즉시 TB_NOTIFY_HISTORY INSERT, 그리고 **아직 처리를 시작하지 않은 나머지 서버는 전부 FAIL(0건 시도)로 기록하고 런 종료**
       (재연결해서 이어가지 않음 — 다음 스케쥴이 FAIL난 서버들의 동일 구간을 자동 재집계)
     → 이 서버 처리가 끝나면(SUCCESS/PARTIAL) 바로 TB_NOTIFY_HISTORY INSERT 후 다음 서버로 진행
        ↓
⑤ 통보 대상 서버를 전부 처리했으면(또는 위 ④의 중단 처리가 끝났으면) connection close
```

---

## 6. 확인되지 않은 사항 (레거시 SMS 운영팀 재확인 필요)

- `bind(01)` TR의 바디 구조: `SMSSUBMIT_BODY`와 동일 레이아웃을 그대로 쓰는지(`sms_code`만 `01`로 채워 보내는 방식인지), 별도 구조가 있는지
- 응답(ack) 페이로드가 실제로 없는지, 혹은 화면 캡처에서 생략됐을 뿐인지
- SMS 게이트웨이의 실제 접속 host/port
- `body_length`가 항상 고정값 `"353"`인지, 가변 길이 바디를 지원하는 케이스가 별도로 있는지

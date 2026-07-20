# PreCheck 통보 TR 연동 정의서 v1.0

---

## 1. 문서 목적

이 문서는 통보 서버가 레거시 SMS 게이트웨이와 통신할 때 사용하는 TR(Bind & Submit, TCP 소켓 기반) 바이트 규격을 정의한다. 원본 규격은 레거시 시스템 화면의 C 구조체 정의(`BISUB_HEADER`, `SMSSUBMIT_BODY`)를 캡처해 확인했으며, bind TR의 바디(`SMSBIND_BODY`)와 응답(`SmsAckPacket`) 구조는 이후 5절/6절 내용을 근거로 역산·보완했다(정확한 필드 폭은 8절 참고 — 레거시 운영팀 재확인 필요).

DB 테이블(`TB_NOTIFY_HISTORY`) 구조는 `통보_DB정의서.md`에서, 통보 스케쥴 conf 파일 포맷은 별도 통보 스케쥴러 정의서에서 다루며, 이 문서는 TR 바이트 레이아웃과 필드 채움값만 다룬다.

---

## 2. 공통 사항

| 항목 | 내용 |
|---|---|
| 연결 방식 | TCP, 비연결형 (스케쥴 실행마다 connection을 새로 열어 bind 1회 + submit N회 처리 후 닫음, connection을 계속 유지하지 않음) |
| 인코딩 | EUC-KR |
| 패딩 문자 | 빈 필드/수치형 빈 자리는 ASCII 스페이스(0x20)로 채움 |
| 응답(ack) | **bind/submit 둘 다 `SmsAckPacket`(7바이트) 응답을 받는다**(5절). bind는 `result` 값을 `"00"`인지 검증한다 — 값 불일치 또는 응답 실패(읽기실패/타임아웃/연결종료) 시 에러. **submit은 `result` 값을 검증하지 않는다** — 7바이트가 정상적으로 도착하기만 하면 그 수신자는 성공, 응답을 못 받은 경우(읽기실패/타임아웃/연결종료)에만 그 수신자 실패로 처리한다 |
| 응답 대기 타임아웃 | 소켓 read timeout을 connect timeout과 동일 값(`SMS_CONNECT_TIMEOUT_MS`, 5000ms)으로 재사용 — "응답 없음"을 이 시간까지 기다렸다가 실패로 판정 |
| TR 1건 전체 길이 | bind 요청: `BISUB_HEADER`(5바이트) + `SMSBIND_BODY`(30바이트) = **35바이트**. submit 요청: `BISUB_HEADER`(5바이트) + `SMSSUBMIT_BODY`(353바이트) = **358바이트**. bind/submit 응답 공통: `SmsAckPacket` **7바이트** |

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
| `body_length` | 3 | 뒤따르는 바디의 길이(바이트) | TR 종류별 고정값 — bind: `"030"`, submit: `"353"` |

---

## 4. SMSBIND_BODY (30바이트, bind 전용)

```c
typedef struct
{
    char system_id[20];   /* 통보 서버(연동 시스템) 식별자 */
    char version[10];     /* 연동 규격 버전 */
} SMSBIND_BODY; /* Bind Body */
```

| 순번 | 필드 | 길이 | 설명 | 통보 서버 채움값 |
|---|---|---|---|---|
| 1 | `system_id` | 20 | 통보 서버(연동 시스템) 식별자 | `"SMS_TUJA01"`(10자) + 우측 스페이스 패딩 10칸 |
| 2 | `version` | 10 | 연동 규격 버전 | `"2.0.0"`(5자) + 우측 스페이스 패딩 5칸 |

> 필드 길이 합계: 20 + 10 = **30바이트** (`BISUB_HEADER.body_length="030"`과 일치)
>
> ⚠️ `system_id` 길이(20바이트)는 `body_length="030"`과 `version[10]` 값을 근거로 역산한 값이다. 원본 레거시 캡처에는 `SMSBIND_BODY`의 필드별 폭이 명시되어 있지 않았으므로, 실제 폭·`system_id` 값이 `"SMS_TUJA01"`이 맞는지는 레거시 SMS 운영팀 확인이 필요하다(8절 참고).

---

## 5. SmsAckPacket (7바이트, bind/submit 공통 응답)

bind와 submit 요청 모두 이 포맷의 응답을 받는다. 단 **값을 검증하는 건 bind뿐**이고, submit은 도착 여부만 확인한다(2절 "응답(ack)" 행 참고).

```c
typedef struct
{
    char sms_code[2];      /* 요청과 동일하게 에코, "01"(bind) 또는 "03"(submit) */
    char body_length[3];   /* 응답 body 길이, "002" 고정 */
} BISUB_HEADER;  /* 응답 헤더 — 요청과 동일 구조체 재사용 */

char result[2];  /* 응답 body: "00"=정상, 그 외=에러코드 */
```

| 필드 | 길이 | 설명 |
|---|---|---|
| `header.sms_code` | 2 | 요청과 동일한 `"01"`/`"03"` 에코 |
| `header.body_length` | 3 | 고정값 `"002"` |
| `result`(body) | 2 | `"00"`=정상, 그 외=에러. **bind만 이 값을 검증함(`"00"` 아니면 실패). submit은 값을 보지 않고 7바이트 수신 성공 여부만 확인함** |

TR 전체 길이: 헤더 5바이트 + body 2바이트 = **7바이트**

> ⚠️ `"00"` 외 에러코드 값 체계(어떤 코드가 어떤 실패를 의미하는지)는 미확인 — 8절 참고. 응답이 안 오거나 7바이트를 다 못 읽고 연결이 끊기는 경우의 처리는 정의됨: read timeout(`SMS_CONNECT_TIMEOUT_MS` 재사용) 내에 못 읽으면 실패로 판정하고 재시도하지 않음(bind는 런 전체 FAIL, submit은 해당 수신자만 실패).

---

## 6. SMSSUBMIT_BODY (353바이트, submit 전용)

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

## 7. 통보 서버 처리 절차 (연결 ~ 종료)

connection은 **스케쥴 1회 실행 전체에서 1개만** 연다(서버별로 따로 열지 않음). 그 안에서 통보 대상 서버를 순서대로 한 서버씩 처리하며, **서버 하나를 끝낼 때마다 즉시 그 서버의 `TB_NOTIFY_HISTORY`를 INSERT한다** (전체 서버를 다 처리한 뒤 한꺼번에 기록하지 않음 — crash-safe).

```
① 통보 스케쥴 도래, 통보 대상 서버 목록 확정(경고/에러 1건 이상인 서버들)
        ↓
② TCP connection 1회 open (SMS 게이트웨이 host:port, 설정값)
        ↓
③ 바인드 처리
   바인드 전송  BISUB_HEADER(sms_code="01", body_length="030") + SMSBIND_BODY(4절 채움값) 전송
   바인드 응답  SmsAckPacket(5절, 7바이트) 수신 -> result "00"이면 정상, 그 외 또는 응답 없음/연결 끊김이면 에러
   - bind 자체가 실패하면 → 통보 대상 서버 전원 FAIL(0건 시도)로 즉시 기록, 런 종료 (④로 진행 안 함)
        ↓
④ 통보 대상 서버를 한 서버씩 순회:
     해당 서버의 수신자 전화번호마다 BISUB_HEADER(sms_code="03", body_length="353") + SMSSUBMIT_BODY(6절 채움값) 전송
     → 전송마다 SmsAckPacket(5절, 7바이트) 수신 대기. 값은 검증하지 않고 7바이트가 정상 도착하면 그 수신자 성공,
       읽기 실패/타임아웃/연결종료면 그 수신자부터 실패(이후 수신자는 시도 안 함, ③의 재연결 안 함 원칙과 동일)
     → 이 서버의 수신자 전원 submit(+ack) 성공 시: NOTIFY_STATUS='SUCCESS'
     → 이 서버 처리 중 connection이 끊기면: 그때까지 성공한 건수 기준 NOTIFY_STATUS='PARTIAL'(1건 이상 성공) 또는 'FAIL'(0건),
       즉시 TB_NOTIFY_HISTORY INSERT, 그리고 **아직 처리를 시작하지 않은 나머지 서버는 전부 FAIL(0건 시도)로 기록하고 런 종료**
       (재연결해서 이어가지 않음 — 다음 스케쥴이 FAIL난 서버들의 동일 구간을 자동 재집계)
     → 이 서버 처리가 끝나면(SUCCESS/PARTIAL) 바로 TB_NOTIFY_HISTORY INSERT 후 다음 서버로 진행
        ↓
⑤ 통보 대상 서버를 전부 처리했으면(또는 위 ④의 중단 처리가 끝났으면) connection close
```

---

## 8. 확인되지 않은 사항 (레거시 SMS 운영팀 재확인 필요)

- `SMSBIND_BODY.system_id`의 실제 폭(현재 20바이트는 `body_length="030"` 기준 역산값)과 실제 채움값이 `"SMS_TUJA01"`이 맞는지
- `SmsAckPacket.result`의 `"00"` 외 에러코드 값 체계(코드별 의미) — 아직 알려진 바 없음
- SMS 게이트웨이의 실제 접속 host/port

> read timeout(5절 "응답 대기 타임아웃")은 내부 구현 결정(connect timeout 재사용)이며, 레거시 게이트웨이의 실제 응답 SLA를 운영팀이 알려주면 값 조정 필요할 수 있음.

# Study Tablet

강의 들을 때 태블릿에서 쓰는 안드로이드 공부 앱 (Kotlin + Jetpack Compose).

## 기능

- **OpenAI API 연결 (공식 방식)** – OpenAI Platform API 키 + 공식 Responses API(`api.openai.com/v1/responses`). 키는 Android Keystore로 암호화해 기기에만 저장합니다. 홈 화면에 이 기기에서 쓴 토큰 사용량(오늘/이번 달)을 표시하고, 전체 사용량·비용은 대시보드 링크로 확인합니다.
- **PDF 뷰어** – 50%~500% 확대/축소: 두 손가락 핀치, 더블탭(100% ↔ 250%), 상단 +/− 버튼, 100% 맞춤 버튼. 확대 시 화면 영역을 고해상도로 다시 렌더링해 글자가 선명합니다. 드래그/플링으로 이동.
- **AI 요약** – PDF 텍스트를 추출해 마크다운 요약(핵심 정리, 예상 질문 포함)을 생성·표시·공유. 문서별로 캐시됩니다.
- **녹음** – 문서별 강의 녹음(포그라운드 서비스), 재생/탐색/배속.
- 태블릿(가로 840dp 이상)에서는 PDF | 요약·녹음 분할 화면, 좁은 화면에서는 탭.

## 사용법

1. https://platform.openai.com/api-keys 에서 API 키 발급 (결제 수단/크레딧 필요).
2. 앱 홈 화면 "OpenAI API" 카드에 키 입력 → "확인 후 저장".
3. PDF 열기 → "AI 요약 만들기".

## 빌드 & 설치

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

요구사항: JDK 17+, Android SDK 36 (`local.properties`의 `sdk.dir`).

## 참고

- API 사용량은 ChatGPT Plus/Pro 구독과 별개로 사용한 만큼 과금됩니다. ChatGPT 구독 한도를 서드파티 앱에서 쓰는 공식 방법은 없습니다.
- 기본 모델은 `gpt-5-mini`이며 홈 화면 "모델" 칸에서 변경 가능(예: `gpt-5`, `gpt-4.1-mini`).
- 앱 내 사용량은 이 기기에서 보낸 요청만 집계합니다.
- 스캔본(이미지) PDF는 텍스트가 없어 요약할 수 없습니다.
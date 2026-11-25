# Study Tablet

강의 들을 때 태블릿에서 쓰는 안드로이드 공부 앱 (Kotlin + Jetpack Compose).

## 기능

- **ChatGPT 로그인 (Codex OAuth)** – Codex CLI와 같은 "Sign in with ChatGPT" PKCE 흐름. ChatGPT 플랜의 Codex 사용량으로 AI 기능을 쓰고, 홈 화면에 사용량(5시간/주간 한도)을 표시합니다.
- **PDF 뷰어** – 페이지 스크롤, 확대(버튼/두 손가락 핀치).
- **AI 요약** – PDF 텍스트를 추출해 Codex 모델로 마크다운 요약(핵심 정리, 예상 질문 포함)을 생성·표시·공유. 문서별로 캐시됩니다.
- **녹음** – 문서별 강의 녹음(백그라운드 포그라운드 서비스), 재생/탐색/배속.
- 태블릿(가로 840dp 이상)에서는 PDF | 요약·녹음 분할 화면, 좁은 화면에서는 탭.

## 빌드 & 설치

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

요구사항: JDK 17+, Android SDK 36 (`local.properties`의 `sdk.dir`).

## 참고 / 주의

- 로그인은 기기 안에서 `http://localhost:1455/auth/callback` 로컬 서버로 콜백을 받습니다(Codex CLI와 동일 포트). 로그인 완료 후 브라우저 페이지의 "앱으로 돌아가기"를 누르세요.
- Codex CLI의 공개 OAuth 클라이언트와 ChatGPT Codex 백엔드(`chatgpt.com/backend-api/codex`)를 사용하는 비공식 방식이라 OpenAI 측 변경으로 동작이 바뀔 수 있습니다.
- 모델이 거부되면 홈 화면의 "모델" 칸에서 바꿔보세요(예: `gpt-5`, `gpt-5-codex`).
- 스캔본(이미지) PDF는 텍스트가 없어 요약할 수 없습니다.
- 토큰은 앱 전용 저장소에 보관됩니다.

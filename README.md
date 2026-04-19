# 🤖 Jarvis-Yul : 다중 API 통합 기반 업무 자동화 파이프라인

![Java](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=github-actions&logoColor=white)
![Notion API](https://img.shields.io/badge/Notion_API-000000?style=for-the-badge&logo=notion&logoColor=white)
![Slack API](https://img.shields.io/badge/Slack-4A154B?style=for-the-badge&logo=slack&logoColor=white)

개인 개발 생산성 향상과 데이터 로깅 자동화를 위해 구축한 **Java 기반 API 통합 파이프라인 엔진**입니다. 
GitHub, Notion, Slack, Google API 등 이기종 시스템을 하나의 비즈니스 로직으로 묶어, 개발자의 개입 없이 100% 자동화된 모니터링 및 기록 환경을 제공합니다.

<br>

## 🏛️ System Architecture

```mermaid
graph TD
    subgraph Trigger
        A[GitHub Actions<br>Cron Schedule]
        B[GitHub Actions<br>Push Event]
    end

    subgraph Core Engine
        C((Jarvis-Yul<br>Java Application))
    end

    subgraph External APIs
        D[GitHub API<br>Commit History]
        E[Notion API<br>Database]
        F[Slack Webhook<br>Notification]
        G[Google Workspace<br>Gmail / News RSS]
    end

    A --> C
    B --> C
    C -- 1. Fetch Commits --> D
    C -- 2. Check Idempotency (SHA) --> E
    C -- 3. Insert 1-Commit 1-Row --> E
    C -- 4. Send Briefing --> F
    C -- 5. Fetch Unread / News --> G

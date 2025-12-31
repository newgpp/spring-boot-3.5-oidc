#### 环境初始化

- docker启动keycloak
```shell
docker run -d --name keycloak -p 8080:8080 -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin quay.io/keycloak/keycloak:25.0 start-dev
```
创建realm 
创建client
创建user


- docker启动redis

```shell
docker run -d --name my-redis -p 6379:6379 redis redis-server --requirepass "123456"
```


#### 交互流程

- jump to sso login

```mermaid
sequenceDiagram
    participant Browser
    participant Frontend
    participant Backend
    participant Keycloak

    Browser ->> Frontend: 点击登录
    Frontend ->> Backend: GET /api/v1/auth/login
    Backend ->> Keycloak: 重定向 /authorize
    Keycloak ->> Browser: 展示登录页
    Browser ->> Keycloak: 输入账号密码
    Keycloak ->> Frontend: redirect_uri?code=xxx

```

- sso login callback get token

```mermaid
sequenceDiagram
    participant Browser
    participant Frontend
    participant Backend
    participant Keycloak
    participant Redis

    Browser ->> Frontend: 携带 code
    Frontend ->> Backend: GET /api/v1/auth/callback?code=xxx

    Backend ->> Keycloak: POST /token (authorization_code)
    Keycloak -->> Backend: access_token / refresh_token / id_token

    Backend ->> Backend: 解析 id_token (JWT)
    Backend ->> Redis: 存 loginToken + 用户信息
    Backend ->> Frontend: 返回 loginToken + 用户信息

```

- refresh token

```mermaid
sequenceDiagram
    participant Frontend
    participant Backend
    participant Redis
    participant Keycloak

    Frontend ->> Backend: POST /refresh (Authorization: Bearer loginToken)

    Backend ->> Redis: 查 loginToken
    Redis -->> Backend: refreshToken

    Backend ->> Keycloak: POST /token (refresh_token)
    Keycloak -->> Backend: 新 access_token / refresh_token

    Backend ->> Redis: 写入新 token
    Backend ->> Redis: 旧 token 延迟过期

    Backend ->> Frontend: 返回新 loginToken

```

- logout

```mermaid
sequenceDiagram
    participant Frontend
    participant Backend
    participant Redis
    participant Keycloak

    Frontend ->> Backend: POST /logout (Bearer token)

    Backend ->> Redis: 读取 refreshToken
    Backend ->> Keycloak: POST /logout
    Keycloak -->> Backend: 200 OK

    Backend ->> Redis: 删除 loginToken
    Backend ->> Frontend: 登出成功

```
#### 环境初始化

- 创建docker网络

```shell
docker network create infra-net
```

- docker启动redis

```shell
docker run -d \
  --name redisdb \
  --network infra-net \
  --restart unless-stopped \
  -p 6379:6379 \
  -v /data/redis:/data \
  redis:7.2 \
  redis-server --requirepass "123456" --appendonly yes

```

```shell
docker run -d \
  --name mariadb \
  --network infra-net \
  --restart unless-stopped \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=123456 \
  -v /data/mariadb:/var/lib/mysql \
  mariadb:10.11

```

- 创建账号

```shell
docker exec -it mariadb mysql -u root -p123456

CREATE DATABASE IF NOT EXISTS keycloak CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'keycloak'@'%' IDENTIFIED BY '123456';
GRANT ALL PRIVILEGES ON keycloak.* TO 'keycloak'@'%';
FLUSH PRIVILEGES;
EXIT;


```

- docker启动keycloak

```shell
docker run -d \
  --name keycloak \
  --network infra-net \
  -p 8080:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_DB=mariadb \
  -e KC_DB_URL=jdbc:mariadb://mariadb:3306/keycloak \
  -e KC_DB_USERNAME=root \
  -e KC_DB_PASSWORD=123456 \
  -e KC_HOSTNAME=localhost \
  -e KC_HOSTNAME_STRICT=false \
  -e KC_HTTP_ENABLED=true \
  quay.io/keycloak/keycloak:25.0 \
  start-dev

```

#### docker构建&启动

```shell
# 构建
docker build -t oidc:1.0 .

# 启动
docker run -d \
  --name oidc \
  --network infra-net \
  -p 10052:10052 \
  oidc:1.0

```

- 配置本地host

```shell
sudo vi /etc/hosts

127.0.0.1       mariadb
127.0.0.1       mongodb
127.0.0.1       redisdb
127.0.0.1       keycloak
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
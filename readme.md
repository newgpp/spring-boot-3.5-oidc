#### keycloak 初始化

- docker启动keycloak
```shell
docker run -d --name keycloak -p 8080:8080 -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin quay.io/keycloak/keycloak:25.0 start-dev
```
- 创建realm
- 创建client
- 创建user


#### 运行服务

访问 http://localhost:8081/login
跳转到 keycloak
登录
跳转回 http://localhost:8081/callback
显示 登录成功

# 规格 10.1 web 服务：node 构建 → nginx:alpine 托管并反代 /api、/ws
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# nginx.conf 由 deploy/ 统一提供（/api、/ws 反代到 server:8080，WS 需 Upgrade 头）
EXPOSE 80

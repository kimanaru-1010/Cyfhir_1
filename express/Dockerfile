# base image
FROM node:18-alpine
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

# add node modules .bin to path
ENV PATH /app/node_modules/.bin:$PATH
EXPOSE 3000

# compile typescript
COPY . .
RUN npm run build

# add app
COPY . .
CMD npm run serve

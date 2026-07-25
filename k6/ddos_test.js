import http from 'k6/http';

const data = JSON.parse(open('./k6-data.json'));
const jwt = data.jwts[0];
const auctionId = data.auctionIds[0];

export const options = {
  scenarios: {
    flood_test: {
      executor: 'constant-vus',
      vus: 50,              // 50 concurrent threads
      duration: '30m',       // Hammer the server non-stop for exactly 30 minutes
    },
  },
};

const url = `http://localhost:80/api/bids`;
const staticPayload = `{"auctionId":"${auctionId}","amount":9999999}`;
const params = {
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${jwt}`,
  },
};

export default function () {
  http.post(url, staticPayload, params);
}

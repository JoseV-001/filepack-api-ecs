import http from 'k6/http';
import { check } from 'k6';
import { FormData } from 'https://jslib.k6.io/formdata/0.0.2/index.js';

// Configuração de carga para forçar AutoScaling
export const options = {
  stages: [
    { duration: '20s', target: 24 },   // Ramp up inicial
    { duration: '70s', target: 36 },   // Sustenta carga pesada por mais de 1 minuto
    { duration: '20s', target: 48 },   // Pico curto para garantir scale-out
    { duration: '10s', target: 0 },    // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<30000'], // 95% das requests < 30s
    http_req_failed: ['rate<0.1'],       // Menos de 10% de falhas
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const FIXTURES = [
  { name: 'performance_benchmark.json', data: open('../data/performance_benchmark.json', 'b'), contentType: 'application/json' },
  { name: 'analytics_data.json', data: open('../data/analytics_data.json', 'b'), contentType: 'application/json' },
  { name: 'api_responses.json', data: open('../data/api_responses.json', 'b'), contentType: 'application/json' },
  { name: 'telemetry_metrics.json', data: open('../data/telemetry_metrics.json', 'b'), contentType: 'application/json' },
];

export default function () {
  const fd = new FormData();

  // Usa arquivos reais do repositório para reproduzir um cenário mais próximo do demo.
  for (const fixture of FIXTURES) {
    fd.append(
      'files',
      http.file(fixture.data, fixture.name, fixture.contentType)
    );
  }
  fd.append('password', 'test123');

  const response = http.post(`${BASE_URL}/api/filepack`, fd.body(), {
    headers: { 'Content-Type': `multipart/form-data; boundary=${fd.boundary}` },
    timeout: '60s',
  });

  check(response, {
    'status is 200': (r) => r.status === 200,
    'response has content': (r) => r.body.length > 0,
    'response is zip': (r) => (r.headers['Content-Type'] || '').includes('application/zip'),
  });
}

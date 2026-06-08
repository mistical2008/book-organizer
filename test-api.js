import http from 'http';

http.get('http://localhost:3000/api/config', (res) => {
  console.log('STATUS:', res.statusCode);
  console.log('HEADERS:', res.headers);
  let data = '';
  res.on('data', (chunk) => { data += chunk; });
  res.on('end', () => {
    console.log('BODY:', data.substring(0, 200));
  });
}).on('error', (err) => {
  console.error('ERROR:', err.message);
});

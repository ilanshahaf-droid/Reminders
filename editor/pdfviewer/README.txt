npm i pdfjs-dist pdf-lib esbuild
npx esbuild entry.js --bundle --platform=browser --format=iife --minify --target=es2019 --outfile=../../app/src/main/assets/pdfviewer.js
cp template.html ../../app/src/main/assets/pdf_viewer.html

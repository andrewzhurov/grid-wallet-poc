const path = require('path');
const MiniCssExtractPlugin = require('mini-css-extract-plugin');

module.exports = {
  entry: './src/lib/worker.ts',
  module: {
    rules: [
      {
        test: /\.tsx?$/,
        use: 'ts-loader',
        exclude: /node_modules/,
      },
      {
        test: /\.css$/,
        use: [
          process.env.NODE_ENV !== 'production' ? 'style-loader' : MiniCssExtractPlugin.loader,
          'css-loader',
          {
            loader: 'postcss-loader',
            options: {
              postcssOptions: {
                plugins: [
                  // Add other PostCSS plugins here if needed
                  process.env.NODE_ENV === 'production' ? require('@fullhuman/postcss-purgecss')({
                    content: [
                      path.resolve(__dirname, 'src/**/*.html'), // Your templates
                      path.resolve(__dirname, 'src/**/*.js')    // Your scripts
                    ],
                    defaultExtractor: content => content.match(/[\w-/:]+(?<!:)/g) || []
                  }) : null
                ].filter(Boolean)
              }
            }
          }
        ]
      },
      {
        test: /\.md$/,
        use: ['html-loader', 'markdown-loader']
      }
    ],
  },
  plugins: [
    new MiniCssExtractPlugin({
      filename: '[name].css',
      chunkFilename: '[id].css',
    }),
  ],
  resolve: {
    extensions: ['.ts', '.js'],
  },
  output: {
    filename: 'worker.js',
    path: path.resolve(__dirname, 'public/js'),
  },
  devtool: 'source-map',
  experiments: {
    asyncWebAssembly: true
  },
  mode: 'development',
  devServer: {
    compress: true,
    port: 9000
  }
};

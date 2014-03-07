# Status
Status is a framework for providing health check like statuses to a java web application.

# Documentation
http://indeedeng.github.io/status

## Building documentation locally

requires ruby 1.9.3+

```bash
$ git checkout gh-pages
$ gem install bundler
$ bundle install
$ rake clean serve
  => open http://localhost:4000/ in browser
```

# Modifying documentation
- All documentation pages' permalink must end with a "/"
    - Without a trailing slash, the content will be served with content-type "application/octect-stream" and will be downloaded instead of displayed in your web browser
    - http://pixelcog.com/blog/2013/jekyll-from-scratch-core-architecture/#pitfalls_with_pretty_urls
- When building a link, use `{{ site.baseurl }}` as the href prefix
    - GOOD: `{{ site.baseurl }}/docs/new/page/`
    - BAD: `/docs/new/page/` - This will work locally but will not work when deployed to `http://indeedeng.github.io/status`
- GFM ([github-flavored-markdown](https://help.github.com/articles/github-flavored-markdown)) is NOT available in the markdown for the documentation.

# References
- http://jekyllrb.com/
- https://help.github.com/articles/using-jekyll-with-pages
- https://help.github.com/articles/pages-don-t-build-unable-to-run-jekyll
- https://github.com/vmg/redcarpet

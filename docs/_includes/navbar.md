{% comment %}
  顶部导航栏（全站共用）：logo + 锚点/子页链接 + 语言切换。
  依赖 _includes/codes.md 提供的 lang / full / parent / is_home 变量（由布局先 include）。
  语言切换规则：优先当前目录的同名语言变体（用 site.pages 做存在性校验），
  该目录没有对应语言文件时回落到目标语言的首页。
{% endcomment %}
{% if full == '中文' %}
  {% assign home_href = site.baseurl | append: '/' %}
  {% assign feats_href = home_href | append: '#features' %}
  {% assign other_label = 'English' %}
  {% if page.dir == '/' %}
    {% assign other_url = '/en.html' %}
  {% else %}
    {% assign other_url = page.dir %}
  {% endif %}
  {% assign other_home = '/en.html' %}
{% else %}
  {% assign home_href = site.baseurl | append: '/en.html' %}
  {% assign feats_href = home_href | append: '#features' %}
  {% assign other_label = '中文' %}
  {% if page.dir == '/' %}
    {% assign other_url = '/' %}
  {% else %}
    {% assign other_url = page.dir | append: 'zh.html' %}
  {% endif %}
  {% assign other_home = '/' %}
{% endif %}
{% if lang == '' or lang == 'en' %}
  {% assign sub_suffix = 'index.html' %}
{% else %}
  {% assign sub_suffix = lang | append: '.html' %}
{% endif %}

{% comment %} 存在性校验：site.pages 里找得到才用同目录变体，否则回落语言首页 {% endcomment %}
{% assign other_exists = false %}
{% for p in site.pages %}
  {% if p.url == other_url %}{% assign other_exists = true %}{% endif %}
{% endfor %}
{% unless other_exists %}{% assign other_url = other_home %}{% endunless %}

<nav class="navbar" role="navigation">
  <div class="nav-container">
    <a class="nav-logo" href="{{ home_href }}">
      <img src="{{ site.baseurl }}/web/256.webp" alt="HowRead Pro" class="logo-icon">
      <span class="logo-text">HowRead Pro{% if full == '中文' %} · 好好读{% endif %}</span>
    </a>
    <div class="nav-links">
      {% if full == '中文' %}
        <a href="{% if is_home %}#features{% else %}{{ feats_href }}{% endif %}">功能特点</a>
        <a href="{{ site.baseurl }}/what-is-new/{{ sub_suffix }}">更新日志</a>
        <a href="{{ site.baseurl }}/faq/{{ sub_suffix }}">常见问题</a>
        <a href="{{ site.baseurl }}/download/{{ sub_suffix }}">下载</a>
        <a href="{{ site.baseurl }}/PrivacyPolicy/{{ sub_suffix }}">隐私政策</a>
        <a href="{{ site.baseurl }}/online-book-reader/" target="_blank">在线阅读</a>
        <a href="{{ site.baseurl }}{{ other_url }}">{{ other_label }}</a>
      {% else %}
        <a href="{% if is_home %}#features{% else %}{{ feats_href }}{% endif %}">Features</a>
        <a href="{{ site.baseurl }}/what-is-new/{{ sub_suffix }}">Changelog</a>
        <a href="{{ site.baseurl }}/faq/{{ sub_suffix }}">FAQ</a>
        <a href="{{ site.baseurl }}/download/{{ sub_suffix }}">Download</a>
        <a href="{{ site.baseurl }}/PrivacyPolicy/{{ sub_suffix }}">Privacy</a>
        <a href="{{ site.baseurl }}/online-book-reader/" target="_blank">Online Reader</a>
        <a href="{{ site.baseurl }}{{ other_url }}">{{ other_label }}</a>
      {% endif %}
    </div>
  </div>
</nav>

/* HowRead Qt 批次三 v0.61.0：docx/odt/rtf → HTML 正文片段。
 * docx/odt：mupdf zip 读（fz_open_archive + fz_read_archive_entry）+ 内置良构 XML 扫描；
 * rtf：内置解析（组栈/目的地跳过/\uN+\ucN/\'hh 按代码页 MultiByteToWideChar）。
 * 与 doc 链同语义：输出 UTF-8 片段，应用侧 openablePath 包 HTML 缓存后打开。纯 C++，无 Qt。 */
#include "mupdf/fitz.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <map>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

namespace
{

/* ---------- 基础工具 ---------- */

static void escAppend(std::string &out, const std::string &s)
{
	for (size_t i = 0; i < s.size(); i++)
	{
		const char c = s[i];
		if (c == '&') out += "&amp;";
		else if (c == '<') out += "&lt;";
		else if (c == '>') out += "&gt;";
		else out += c;
	}
}

static void utf8cp(unsigned int cp, std::string &out)
{
	if (cp < 0x80) out += (char)cp;
	else if (cp < 0x800)
	{
		out += (char)(0xC0 | (cp >> 6));
		out += (char)(0x80 | (cp & 0x3F));
	}
	else if (cp < 0x10000)
	{
		out += (char)(0xE0 | (cp >> 12));
		out += (char)(0x80 | ((cp >> 6) & 0x3F));
		out += (char)(0x80 | (cp & 0x3F));
	}
	else
	{
		out += (char)(0xF0 | (cp >> 18));
		out += (char)(0x80 | ((cp >> 12) & 0x3F));
		out += (char)(0x80 | ((cp >> 6) & 0x3F));
		out += (char)(0x80 | (cp & 0x3F));
	}
}

/* 实体解码（&amp;&lt;&gt;&quot;&apos;&#NNN;&#xHHH;），其余原样拷贝 */
static void decodeEnt(const std::string &in, std::string &out)
{
	size_t i = 0;
	while (i < in.size())
	{
		if (in[i] == '&')
		{
			const size_t sc = in.find(';', i);
			if (sc != std::string::npos && sc - i <= 10)
			{
				const std::string e = in.substr(i + 1, sc - i - 1);
				if (e == "amp") { out += '&'; i = sc + 1; continue; }
				if (e == "lt") { out += '<'; i = sc + 1; continue; }
				if (e == "gt") { out += '>'; i = sc + 1; continue; }
				if (e == "quot") { out += '"'; i = sc + 1; continue; }
				if (e == "apos") { out += '\''; i = sc + 1; continue; }
				if (e.size() > 1 && e[0] == '#')
				{
					unsigned long cp = 0;
					bool ok = true;
					if (e[1] == 'x' || e[1] == 'X')
					{
						for (size_t k = 2; k < e.size(); k++)
						{
							const char c = e[k];
							int d = (c >= '0' && c <= '9') ? c - '0'
								: (c >= 'a' && c <= 'f') ? c - 'a' + 10
								: (c >= 'A' && c <= 'F') ? c - 'A' + 10 : -1;
							if (d < 0) { ok = false; break; }
							cp = cp * 16 + (unsigned long)d;
						}
					}
					else
					{
						for (size_t k = 1; k < e.size(); k++)
						{
							if (e[k] < '0' || e[k] > '9') { ok = false; break; }
							cp = cp * 10 + (unsigned long)(e[k] - '0');
						}
					}
					if (ok && cp > 0 && cp < 0x110000) { utf8cp((unsigned int)cp, out); i = sc + 1; continue; }
				}
			}
		}
		out += in[i++];
	}
}

static const char *findStr(const char *h, size_t hn, const char *nd, size_t nn)
{
	if (hn < nn) return NULL;
	for (size_t i = 0; i + nn <= hn; i++)
		if (memcmp(h + i, nd, nn) == 0) return h + i;
	return NULL;
}

static void normWs(std::string &t)
{
	for (size_t i = 0; i < t.size(); i++)
		if (t[i] == '\r' || t[i] == '\n' || t[i] == '\t') t[i] = ' ';
}

/* ---------- 极简 XML 扫描（document.xml / content.xml 良构 XML 足够） ---------- */
struct XmlAttr { std::string n, v; };

static std::string attrGet(const std::vector<XmlAttr> &attrs, const char *n)
{
	for (size_t i = 0; i < attrs.size(); i++)
		if (attrs[i].n == n) return attrs[i].v;
	return std::string();
}

struct XmlTok
{
	const char *p, *e;
	XmlTok(const char *s, size_t n) : p(s), e(s + n) {}
	/* 0=eof 1=start 2=end 3=text */
	int next(std::string &text, std::string &name, std::vector<XmlAttr> &attrs,
			 bool &selfClose, bool &isEnd)
	{
		text.clear(); name.clear(); attrs.clear(); selfClose = false; isEnd = false;
		for (;;)
		{
			if (p >= e) return 0;
			if (*p != '<')
			{
				const char *q = p;
				while (q < e && *q != '<') q++;
				decodeEnt(std::string(p, q - p), text);
				p = q;
				return 3;
			}
			if (e - p >= 4 && memcmp(p, "<!--", 4) == 0)
			{
				const char *q = findStr(p + 4, (size_t)(e - p - 4), "-->", 3);
				p = q ? q + 3 : e;
				continue;
			}
			if (e - p >= 2 && p[1] == '?')
			{
				const char *q = findStr(p + 2, (size_t)(e - p - 2), "?>", 2);
				p = q ? q + 2 : e;
				continue;
			}
			if (e - p >= 2 && p[1] == '!')
			{
				const char *q = (const char *)memchr(p, '>', (size_t)(e - p));
				p = q ? q + 1 : e;
				continue;
			}
			p++;
			if (p < e && *p == '/') { isEnd = true; p++; }
			const char *q = p;
			while (q < e && *q != ' ' && *q != '\t' && *q != '\r' && *q != '\n' && *q != '>' && *q != '/') q++;
			name.assign(p, q - p);
			p = q;
			while (p < e && *p != '>')
			{
				if (*p == '/' && p + 1 < e && p[1] == '>') { selfClose = true; p += 2; break; }
				while (p < e && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) p++;
				if (p >= e || *p == '>') break;
				const char *a = p;
				while (p < e && *p != '=' && *p != '>' && *p != ' ' && *p != '\t' && *p != '\r' && *p != '\n' && *p != '/') p++;
				XmlAttr at;
				at.n.assign(a, p - a);
				if (p < e && *p == '=')
				{
					p++;
					char qc = 0;
					if (p < e && (*p == '"' || *p == '\'')) qc = *p++;
					const char *v = p;
					while (p < e)
					{
						if (qc) { if (*p == qc) break; }
						else if (*p == '>' || *p == ' ') break;
						p++;
					}
					decodeEnt(std::string(v, p - v), at.v);
					if (qc && p < e) p++;
				}
				if (at.n.empty()) { if (p < e && *p != '>' && *p != '/') p++; else break; }
				else attrs.push_back(at);
			}
			if (!selfClose && p < e && *p == '>') p++;
			return isEnd ? 2 : 1;
		}
	}
};

/* ---------- docx（word/document.xml；v0.104.0 P1-3：+表格 +图片 base64 内嵌） ---------- */

/* zipEntry 前置声明（实现见下方 zip 区） */
static bool zipEntry(const char *path, const char *entry, std::string &data);

/* base64（图片内嵌 data URI；引擎 html-parse.c 只认 jpeg/png/gif 三种） */
static void b64Append(const unsigned char *d, size_t n, std::string &out)
{
	static const char *tb =
		"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
	for (size_t i = 0; i < n; i += 3)
	{
		unsigned v = (unsigned)d[i] << 16;
		if (i + 1 < n) v |= (unsigned)d[i + 1] << 8;
		if (i + 2 < n) v |= (unsigned)d[i + 2];
		out += tb[(v >> 18) & 63];
		out += tb[(v >> 12) & 63];
		out += (i + 1 < n) ? tb[(v >> 6) & 63] : '=';
		out += (i + 2 < n) ? tb[v & 63] : '=';
	}
}

static std::string mimeOf(const std::string &fn)
{
	std::string s = fn;
	for (size_t i = 0; i < s.size(); i++) s[i] = (char)tolower((unsigned char)s[i]);
	if (s.size() >= 5 && s.rfind(".jpeg") == s.size() - 5) return "image/jpeg";
	if (s.size() >= 4 && s.rfind(".jpg") == s.size() - 4) return "image/jpeg";
	if (s.size() >= 4 && s.rfind(".png") == s.size() - 4) return "image/png";
	if (s.size() >= 4 && s.rfind(".gif") == s.size() - 4) return "image/gif";
	return std::string();
}

/* word/_rels/document.xml.rels：rId → 归档内路径（Target 相对 word/） */
static void docxRels(const std::string &rx, std::map<std::string, std::string> &m)
{
	XmlTok t(rx.data(), rx.size());
	std::string text, name;
	std::vector<XmlAttr> attrs;
	bool sc = false, ie = false;
	for (;;)
	{
		const int k = t.next(text, name, attrs, sc, ie);
		if (k == 0) break;
		if (k == 1 && name == "Relationship")
		{
			const std::string id = attrGet(attrs, "Id");
			std::string tg = attrGet(attrs, "Target");
			if (!id.empty() && !tg.empty())
			{
				if (tg[0] == '/') tg = tg.substr(1);
				if (tg.compare(0, 5, "word/") != 0) tg = "word/" + tg;
				m[id] = tg;
			}
		}
	}
}

static int headingLevel(const std::string &style)
{
	std::string s = style;
	for (size_t i = 0; i < s.size(); i++) s[i] = (char)tolower((unsigned char)s[i]);
	int lvl = 0;
	if (s.find("heading") != std::string::npos || s.find("\xe6\xa0\x87\xe9\xa2\x98") != std::string::npos)
	{
		for (size_t i = 0; i < s.size(); i++)
			if (s[i] >= '1' && s[i] <= '6') { lvl = s[i] - '0'; break; }
		if (lvl == 0) lvl = 2;
	}
	else if (s == "title") lvl = 1;
	else if (s == "subtitle") lvl = 3;
	else if (s.size() == 1 && s[0] >= '1' && s[0] <= '6') lvl = s[0] - '0';
	if (lvl < 1 || lvl > 6) lvl = 0;
	return lvl;
}

static void docxXmlToHtml(const char *xml, size_t n, const char *docxPath,
						  const std::map<std::string, std::string> &rels, std::string &out)
{
	XmlTok t(xml, n);
	std::string text, name, para;
	std::vector<XmlAttr> attrs;
	bool sc = false, ie = false;
	bool inP = false, inT = false, fb = false, fi = false, fu = false;
	int heading = 0;
	/* v0.104.0：表格（嵌套表只发最外层边界）+ 图片总量护栏 64MB */
	int tblDepth = 0, trOpen = 0, tcOpen = 0;
	int imgBytes = 0;
	for (;;)
	{
		const int k = t.next(text, name, attrs, sc, ie);
		if (k == 0) break;
		if (k == 3)
		{
			if (inP && inT)
			{
				std::string norm = text;
				normWs(norm);
				if (fb || fi || fu)
				{
					if (fb) para += "<b>";
					if (fi) para += "<i>";
					if (fu) para += "<u>";
					escAppend(para, norm);
					if (fu) para += "</u>";
					if (fi) para += "</i>";
					if (fb) para += "</b>";
				}
				else escAppend(para, norm);
			}
			continue;
		}
		if (k == 1)
		{
			if (name == "w:p") { inP = true; para.clear(); heading = 0; fb = fi = fu = false; inT = false; }
			else if (name == "w:pStyle") heading = headingLevel(attrGet(attrs, "w:val"));
			else if (name == "w:t") inT = true;
			else if (name == "w:r") fb = fi = fu = false;
			else if (name == "w:b") { const std::string v = attrGet(attrs, "w:val"); fb = (v.empty() || v == "1" || v == "on" || v == "true"); }
			else if (name == "w:i") { const std::string v = attrGet(attrs, "w:val"); fi = (v.empty() || v == "1" || v == "on" || v == "true"); }
			else if (name == "w:u") { const std::string v = attrGet(attrs, "w:val"); fu = (v.empty() || v == "1" || v == "on" || v == "true"); }
			else if (name == "w:br") { if (inP) para += "<br/>"; }
			else if (name == "w:tab") { if (inP) para += "&nbsp;&nbsp;&nbsp;&nbsp;"; }
			/* v0.104.0：表格边界 */
			else if (name == "w:tbl")
			{
				if (tblDepth == 0)
				{
					if (inP && !para.empty()) { out += "<p>"; out += para; out += "</p>"; }
					inP = false; para.clear();
					out += "<table border=\"1\" cellspacing=\"0\" cellpadding=\"3\">";
				}
				tblDepth++;
			}
			else if (name == "w:tr") { if (tblDepth == 1) { out += "<tr>"; trOpen = 1; } }
			else if (name == "w:tc") { if (tblDepth == 1 && trOpen) { out += "<td>"; tcOpen = 1; } }
			/* v0.104.0：图片（a:blip r:embed / 旧式 v:imagedata r:id）→ base64 data URI */
			else if (name == "a:blip" || name == "v:imagedata")
			{
				std::string rid = attrGet(attrs, "r:embed");
				if (rid.empty()) rid = attrGet(attrs, "r:id");
				std::map<std::string, std::string>::const_iterator it =
					rid.empty() ? rels.end() : rels.find(rid);
				if (it != rels.end() && inP && imgBytes < 64 * 1024 * 1024)
				{
					std::string data;
					if (zipEntry(docxPath, it->second.c_str(), data) && !data.empty())
					{
						const std::string mime = mimeOf(it->second);
						if (!mime.empty())
						{
							para += "<img src=\"data:" + mime + ";base64,";
							b64Append((const unsigned char *)data.data(), data.size(), para);
							para += "\"/>";
							imgBytes += (int)data.size();
						}
					}
				}
			}
			/* w:instrText/w:delText/w:fldChar 等不取文本（inT 只认 w:t） */
		}
		else
		{
			if (name == "w:t") inT = false;
			else if (name == "w:p")
			{
				if (inP && !para.empty())
				{
					char ob[16];
					if (heading > 0)
					{
						snprintf(ob, sizeof ob, "h%d", heading);
						out += "<"; out += ob; out += ">";
						out += para;
						out += "</"; out += ob; out += ">";
					}
					else
					{
						out += "<p>"; out += para; out += "</p>";
					}
				}
				inP = false; inT = false; para.clear();
			}
			else if (name == "w:tc") { if (tcOpen) { out += "</td>"; tcOpen = 0; } }
			else if (name == "w:tr") { if (trOpen) { out += "</tr>"; trOpen = 0; } }
			else if (name == "w:tbl")
			{
				tblDepth--;
				if (tblDepth <= 0) { tblDepth = 0; out += "</table>"; }
			}
		}
	}
	if (tcOpen) out += "</td>";
	if (trOpen) out += "</tr>";
	while (tblDepth-- > 0) out += "</table>";
}

/* ---------- odt（content.xml） ---------- */

static std::string localName(const std::string &n)
{
	const size_t c = n.rfind(':');
	return c == std::string::npos ? n : n.substr(c + 1);
}

static void odtXmlToHtml(const char *xml, size_t n, std::string &out)
{
	XmlTok t(xml, n);
	std::string text, name, para;
	std::vector<XmlAttr> attrs;
	bool sc = false, ie = false;
	bool inP = false, isH = false, inItem = false;
	int hlvl = 2, skip = 0;
	auto flushPara = [&]()
	{
		if (!para.empty())
		{
			if (isH && hlvl >= 1 && hlvl <= 6)
			{
				const std::string tag = std::string("h") + (char)('0' + hlvl);
				out += "<" + tag + ">" + para + "</" + tag + ">";
			}
			else out += "<p>" + para + "</p>";
		}
		para.clear();
	};
	for (;;)
	{
		const int k = t.next(text, name, attrs, sc, ie);
		if (k == 0) break;
		const std::string ln = localName(name);
		if (k == 3)
		{
			if (inP && skip == 0)
			{
				std::string norm = text;
				normWs(norm);
				escAppend(para, norm);
			}
			continue;
		}
		if (k == 1)
		{
			if (skip > 0) { if (!sc) skip++; continue; }
			if (ln == "p" || ln == "h")
			{
				if (inP) flushPara();
				inP = true;
				isH = (ln == "h");
				para.clear();
				if (isH)
				{
					const int l = atoi(attrGet(attrs, "outline-level").c_str());
					hlvl = (l >= 1 && l <= 6) ? l : 2;
				}
				if (inItem) para += "\xe2\x80\xa2 "; /* • */
			}
			else if (ln == "list-item") inItem = true;
			else if (ln == "line-break") { if (inP) para += "<br/>"; }
			else if (ln == "tab") { if (inP) para += "&nbsp;&nbsp;&nbsp;&nbsp;"; }
			else if (ln == "s")
			{
				int c = atoi(attrGet(attrs, "c").c_str());
				if (c <= 0) c = 1;
				for (int z = 0; z < c && z < 20; z++) para += ' ';
			}
			else if (ln == "note" || ln == "annotation" || ln == "tracked-changes" ||
					 ln == "frame" || ln == "text-box" || ln == "number" ||
					 ln == "change" || ln == "change-start" || ln == "change-end" ||
					 ln == "sequence-decls" || ln == "variable-decls")
			{
				if (!sc) skip = 1;
			}
			/* span/a/其它：文本照收 */
		}
		else
		{
			if (skip > 0) { skip--; continue; }
			if (ln == "p" || ln == "h") { flushPara(); inP = false; }
			else if (ln == "list-item") inItem = false;
		}
	}
	if (inP) flushPara();
}

/* ---------- rtf ---------- */

struct RtfGrp { int ucskip; bool b, i, u, skip; };

static void rtfFlushRun(std::string &out, std::vector<unsigned char> &pend, int cp,
						bool b, bool i, bool u, bool &inPar)
{
	if (pend.empty()) return;
	std::string txt;
#ifdef _WIN32
	if (cp == 65001) txt.assign((const char *)&pend[0], pend.size());
	else
	{
		int wn = MultiByteToWideChar(cp, 0, (const char *)&pend[0], (int)pend.size(), NULL, 0);
		if (wn > 0)
		{
			std::wstring w((size_t)wn, L'\0');
			MultiByteToWideChar(cp, 0, (const char *)&pend[0], (int)pend.size(), &w[0], wn);
			int un = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), wn, NULL, 0, NULL, NULL);
			if (un > 0)
			{
				std::string u8((size_t)un, '\0');
				WideCharToMultiByte(CP_UTF8, 0, w.c_str(), wn, &u8[0], un, NULL, NULL);
				txt.swap(u8);
			}
		}
	}
#else
	txt.assign((const char *)&pend[0], pend.size());
#endif
	pend.clear();
	if (txt.empty()) return;
	if (!inPar) { out += "<p>"; inPar = true; }
	if (u) out += "<u>";
	if (i) out += "<i>";
	if (b) out += "<b>";
	escAppend(out, txt);
	if (b) out += "</b>";
	if (i) out += "</i>";
	if (u) out += "</u>";
}

static int hexv(char c)
{
	if (c >= '0' && c <= '9') return c - '0';
	if (c >= 'a' && c <= 'f') return c - 'a' + 10;
	if (c >= 'A' && c <= 'F') return c - 'A' + 10;
	return -1;
}

static bool isRtfDest(const std::string &w)
{
	static const char *dests[] = {
		"fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header",
		"headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
		"footnote", "themedata", "colorschememapping", "listtable", "listoverridetable",
		"rsidtbl", "generator", "xmlnstbl", "filetbl", "pgptbl", "pgdsctbl",
		"latentstyles", "datafield", "fldinst", "fchars", "lchars", "ftncn", "aftncn",
		"shpinst", "mmathPr", "wgrffmtfilter", "pntext", "pn", "panose", "nonshppict",
		"objdata", "result", "private", "do", NULL };
	for (int i = 0; dests[i]; i++)
		if (w == dests[i]) return true;
	return false;
}

static void rtfToHtml(const char *s, size_t n, std::string &out)
{
	std::vector<RtfGrp> st;
	RtfGrp g;
	g.ucskip = 1; g.b = g.i = g.u = g.skip = false;
	st.push_back(g);
	int cp = 1252;
	int skipChars = 0;
	std::vector<unsigned char> pend;
	bool inPar = false;
	size_t i = 0;
	while (i < n)
	{
		const unsigned char c = (unsigned char)s[i];
		const bool skb = st.back().skip;
		const bool cb = st.back().b, ci = st.back().i, cu = st.back().u;
		if (c == '{')
		{
			rtfFlushRun(out, pend, cp, cb, ci, cu, inPar);
			RtfGrp ng = st.back();
			ng.skip = false;
			st.push_back(ng);
			i++;
			continue;
		}
		if (c == '}')
		{
			rtfFlushRun(out, pend, cp, cb, ci, cu, inPar);
			if (st.size() > 1) st.pop_back();
			i++;
			continue;
		}
		if (c == '\\')
		{
			if (i + 1 >= n) break;
			const char c1 = s[i + 1];
			if (c1 == '\'')
			{
				if (i + 3 < n && hexv(s[i + 2]) >= 0 && hexv(s[i + 3]) >= 0)
				{
					if (skipChars > 0) skipChars--;
					else if (!skb) pend.push_back((unsigned char)(hexv(s[i + 2]) * 16 + hexv(s[i + 3])));
					i += 4;
				}
				else i += 2;
				continue;
			}
			if (c1 == '*') { st.back().skip = true; i += 2; continue; }
			if (isalpha((unsigned char)c1))
			{
				size_t j = i + 1;
				while (j < n && isalpha((unsigned char)s[j])) j++;
				const std::string w(s + i + 1, j - (i + 1));
				long param = 0;
				bool hasP = false, neg = false;
				if (j < n && s[j] == '-') { neg = true; j++; }
				while (j < n && s[j] >= '0' && s[j] <= '9') { param = param * 10 + (s[j] - '0'); hasP = true; j++; }
				if (neg) param = -param;
				if (j < n && s[j] == ' ') j++;
				i = j;
				if (w == "u" && hasP)
				{
					long cpv = param;
					if (cpv < 0) cpv += 65536;
					if (!skb && cpv > 0 && cpv < 0x110000)
					{
						if (!inPar) { out += "<p>"; inPar = true; }
						utf8cp((unsigned int)cpv, out);
					}
					skipChars = st.back().ucskip;
				}
				else if (w == "uc" && hasP) st.back().ucskip = (int)param;
				else if (w == "ansicpg" && hasP) { if (param >= 100 && param <= 65536) cp = (int)param; }
				else if (w == "bin" && hasP)
				{
					long sk = param > 0 ? param : 0;
					i += (size_t)sk;
					if (i > n) i = n;
				}
				else if (skb)
				{
					/* 目的地内容内其余控制字忽略 */
				}
				else if (w == "par" || w == "sect")
				{
					rtfFlushRun(out, pend, cp, st.back().b, st.back().i, st.back().u, inPar);
					if (inPar) { out += "</p>"; inPar = false; }
				}
				else if (w == "line")
				{
					rtfFlushRun(out, pend, cp, st.back().b, st.back().i, st.back().u, inPar);
					if (!inPar) { out += "<p>"; inPar = true; }
					out += "<br/>";
				}
				else if (w == "tab")
				{
					if (!inPar) { out += "<p>"; inPar = true; }
					out += "&nbsp;&nbsp;&nbsp;&nbsp;";
				}
				else if (w == "b") st.back().b = !(hasP && param == 0);
				else if (w == "i") st.back().i = !(hasP && param == 0);
				else if (w == "ul") st.back().u = !(hasP && param == 0);
				else if (w == "ulnone" || w == "plain" || w == "pard") st.back().b = st.back().i = st.back().u = false;
				else if (w == "emdash") out += "\xe2\x80\x94";
				else if (w == "endash") out += "\xe2\x80\x93";
				else if (w == "lquote") out += "\xe2\x80\x98";
				else if (w == "rquote") out += "\xe2\x80\x99";
				else if (w == "ldblquote") out += "\xe2\x80\x9c";
				else if (w == "rdblquote") out += "\xe2\x80\x9d";
				else if (w == "bullet") out += "\xe2\x80\xa2";
				else if (isRtfDest(w)) st.back().skip = true;
				continue;
			}
			if (c1 == '~')
			{
				if (!skb) { if (!inPar) { out += "<p>"; inPar = true; } out += "&nbsp;"; }
				i += 2;
				continue;
			}
			if (c1 == '\\') { if (!skb) pend.push_back('\\'); i += 2; continue; }
			if (c1 == '{') { if (!skb) pend.push_back('{'); i += 2; continue; }
			if (c1 == '}') { if (!skb) pend.push_back('}'); i += 2; continue; }
			if (c1 == '-')
			{
				if (!skb) { if (!inPar) { out += "<p>"; inPar = true; } out += "-"; }
				i += 2;
				continue;
			}
			i += 2;
			continue;
		}
		if (c == '\r' || c == '\n') { i++; continue; }
		if (skipChars > 0) { skipChars--; i++; continue; }
		if (!skb) pend.push_back(c);
		i++;
	}
	rtfFlushRun(out, pend, cp, st.back().b, st.back().i, st.back().u, inPar);
	if (inPar) out += "</p>";
}

/* ---------- 文件 IO（Windows 中文路径 _wfopen）与 zip 读 ---------- */

static bool writeUtf8(const char *path, const std::string &data)
{
#ifdef _WIN32
	const int wn = MultiByteToWideChar(CP_UTF8, 0, path, -1, NULL, 0);
	if (wn <= 0) return false;
	std::wstring w((size_t)wn, L'\0');
	MultiByteToWideChar(CP_UTF8, 0, path, -1, &w[0], wn);
	FILE *f = _wfopen(w.c_str(), L"wb");
#else
	FILE *f = fopen(path, "wb");
#endif
	if (!f) return false;
	const bool ok = fwrite(data.data(), 1, data.size(), f) == data.size();
	fclose(f);
	return ok;
}

static bool readAllBin(const char *path, std::string &data)
{
#ifdef _WIN32
	const int wn = MultiByteToWideChar(CP_UTF8, 0, path, -1, NULL, 0);
	if (wn <= 0) return false;
	std::wstring w((size_t)wn, L'\0');
	MultiByteToWideChar(CP_UTF8, 0, path, -1, &w[0], wn);
	FILE *f = _wfopen(w.c_str(), L"rb");
#else
	FILE *f = fopen(path, "rb");
#endif
	if (!f) return false;
	char buf[65536];
	size_t r;
	while ((r = fread(buf, 1, sizeof buf, f)) > 0) data.append(buf, r);
	fclose(f);
	return true;
}

/* P2 适配（B2）：zip 包内首书条目解出——遍历归档、扩展名白名单、basename 防穿越；
 * 解出到 destDir/<basename>，outName 回填 basename。返回 0=ok。 */
extern "C" int hr_qt_zip_first_book(const char *zipPath, const char *extsCsv,
                         const char *destDir, char *outName, int outLen)
{
	if (!zipPath || !extsCsv || !destDir || !outName || outLen <= 0) return 1;
	fz_context *ctx = fz_new_context(NULL, NULL, FZ_STORE_UNLIMITED);
	if (!ctx) return 2;
	fz_archive *arch = NULL;
	fz_buffer *buf = NULL;
	int rc = 3;
	char full[1024] = {0};
	char base[512] = {0};
	fz_try(ctx)
	{
		arch = fz_open_archive(ctx, zipPath);
		const int n = arch ? fz_count_archive_entries(ctx, arch) : 0;
		for (int i = 0; i < n && !full[0]; i++)
		{
			const char *nm = fz_list_archive_entry(ctx, arch, i);
			if (!nm) continue;
			const char *dot = strrchr(nm, '.');
			if (!dot) continue;
			const char *e = extsCsv;
			int match = 0;
			while (*e && !match)
			{
				const char *sep = strchr(e, ',');
				const size_t el = sep ? (size_t)(sep - e) : strlen(e);
				if (el == strlen(dot + 1))
				{
					size_t k;
					for (k = 0; k < el; k++)
						if (tolower((unsigned char)e[k]) !=
						    tolower((unsigned char)dot[1 + k]))
							break;
					if (k == el) match = 1;
				}
				e = sep ? sep + 1 : e + el;
			}
			if (!match) continue;
			if (strstr(nm, "..")) continue;
			const char *b = strrchr(nm, '/');
			b = b ? b + 1 : nm;
			if (!b[0]) continue;
			snprintf(full, sizeof full, "%s", nm);
			snprintf(base, sizeof base, "%s", b);
		}
		if (!full[0])
		{
			rc = 4;
		}
		else
		{
			buf = fz_read_archive_entry(ctx, arch, full);
			if (!buf || !buf->len)
				rc = 5;
			else
			{
				wchar_t wdest[1024] = {0};
				char dest[1200];
				snprintf(dest, sizeof dest, "%s/%s", destDir, base);
				MultiByteToWideChar(CP_UTF8, 0, dest, -1, wdest, 1024);
				FILE *f = _wfopen(wdest, L"wb");
				if (!f)
					rc = 6;
				else
				{
					fwrite(buf->data, 1, buf->len, f);
					fclose(f);
					snprintf(outName, outLen, "%s", base);
					rc = 0;
				}
			}
		}
	}
	fz_always(ctx)
	{
		fz_drop_buffer(ctx, buf);
		fz_drop_archive(ctx, arch);
	}
	fz_catch(ctx)
	{
		rc = 7;
	}
	fz_drop_context(ctx);
	return rc;
}

static bool zipEntry(const char *path, const char *entry, std::string &data)
{
	fz_context *ctx = fz_new_context(NULL, NULL, FZ_STORE_UNLIMITED);
	if (!ctx) return false;
	fz_archive *arch = NULL;
	fz_buffer *buf = NULL;
	int ok = 0;
	fz_try(ctx)
	{
		arch = fz_open_archive(ctx, path);
		if (!arch) { ok = 0; }
		else
		{
			buf = fz_read_archive_entry(ctx, arch, entry);
			data.assign((const char *)buf->data, buf->len);
			ok = !data.empty();
		}
	}
	fz_always(ctx)
	{
		fz_drop_buffer(ctx, buf);
		fz_drop_archive(ctx, arch);
	}
	fz_catch(ctx)
	{
		ok = 0;
	}
	fz_drop_context(ctx);
	return ok != 0;
}

} // namespace

extern "C" int hr_qt_docx_to_html(const char *input, const char *output)
{
	if (!input || !output) return 0;
	/* v0.104.0：rels + 图片媒体表（无 rels/无图片照走纯文本路径） */
	std::map<std::string, std::string> rels;
	{
		std::string rx;
		if (zipEntry(input, "word/_rels/document.xml.rels", rx) && !rx.empty())
			docxRels(rx, rels);
	}
	std::string xml;
	if (!zipEntry(input, "word/document.xml", xml)) return 0;
	std::string frag;
	docxXmlToHtml(xml.data(), xml.size(), input, rels, frag);
	if (frag.find('<') == std::string::npos) return 0;
	return writeUtf8(output, frag) ? 1 : 0;
}

extern "C" int hr_qt_odt_to_html(const char *input, const char *output)
{
	std::string xml;
	if (!input || !output) return 0;
	if (!zipEntry(input, "content.xml", xml)) return 0;
	std::string frag;
	odtXmlToHtml(xml.data(), xml.size(), frag);
	if (frag.find('<') == std::string::npos) return 0;
	return writeUtf8(output, frag) ? 1 : 0;
}

extern "C" int hr_qt_rtf_to_html(const char *input, const char *output)
{
	std::string rtf;
	if (!input || !output) return 0;
	if (!readAllBin(input, rtf) || rtf.empty()) return 0;
	std::string frag;
	rtfToHtml(rtf.data(), rtf.size(), frag);
	if (frag.find('<') == std::string::npos) return 0;
	return writeUtf8(output, frag) ? 1 : 0;
}

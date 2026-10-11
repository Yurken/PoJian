\NeedsTeXFormat{LaTeX2e}
\ProvidesClass{cgscic}[2026/10/11 China Graduate Cybersecurity Innovation Competition]
\LoadClass[a4paper,12pt]{article}
% 根据用户提供的 cgscic 模板整理；Fandol 字体使中文排版可跨平台复现。
\RequirePackage[UTF8,fontset=fandol]{ctex}
\RequirePackage{geometry}
\geometry{a4paper,left=2.86cm,right=2.59cm,top=2.5cm,bottom=2.5cm}
\RequirePackage{xeCJKfntef}
\ExplSyntaxOn
\msg_redirect_name:nnn {fontspec} {no-script} {info}
\ExplSyntaxOff
\RequirePackage{ragged2e}
\ctexset{punct=quanjiao}
\newlength{\@projwidth}
\newcommand{\projectname}[1]{\def\@projectname{#1}}
\newcommand{\submissiondate}[1]{\def\@submissiondate{#1}}
\projectname{}
\submissiondate{}
\newcommand{\makecover}{%
  \begin{titlepage}
    \begin{center}
      \vspace*{3cm}
      {\heiti\bfseries\zihao{1}中国研究生网络安全创新大赛\\[0.5em]作品报告}
    \end{center}
    \vfill
    \begin{center}
      \makebox[14cm][l]{%
        {\heiti\bfseries\zihao{3}作品名称：%
          \settowidth{\@projwidth}{\@projectname}%
          \ifdim\@projwidth>11cm
            \parbox[t]{11cm}{\raggedright\CJKunderline{\@projectname\hfill}}%
          \else
            \underline{\makebox[11cm][l]{\@projectname}}%
          \fi}}
      \par\vspace{0.5cm}
      \makebox[14cm][l]{%
        {\heiti\bfseries\zihao{3}提交日期：%
          \settowidth{\@projwidth}{\@submissiondate}%
          \ifdim\@projwidth>11cm
            \parbox[t]{11cm}{\raggedright\CJKunderline{\@submissiondate}}%
          \else
            \underline{\makebox[11cm][l]{\@submissiondate}}%
          \fi}}
    \end{center}
    \vspace{5cm}
  \end{titlepage}}
\newcommand{\makeinstructions}{%
  \clearpage
  \begin{center}{\heiti\bfseries\zihao{3}填写说明}\end{center}
  {\songti\zihao{-4}\linespread{1.5}\selectfont\justifying
    \setlength{\parindent}{0pt}
    1. 所有参赛项目必须为一个基本完整的设计。作品报告书旨在能够清晰准确地阐述（或图示）该参赛队的参赛项目（或方案）。\par\medskip
    2. 作品报告采用A4纸撰写。除标题外，所有内容必需为宋体、小四号字、1.5倍行距。\par\medskip
    3. 作品报告中各项目说明文字部分仅供参考，作品报告书撰写完毕后，请删除所有说明文字。(本页不删除)\par\medskip
    4. 作品报告模板里已经列的内容仅供参考，作者可以在此基础上增加内容或对文档结构进行微调。\par\medskip
    5. 为保证网评的公平、公正，作品报告中应避免出现作者所在学校、院系和指导教师等泄露身份的信息。一经发现，取消作品参赛资格。\par}
  \clearpage}
\RequirePackage{titlesec}
\RequirePackage{titletoc}
\RequirePackage{etoolbox}
\renewcommand{\thesection}{\chinese{section}}
\renewcommand{\thesubsection}{\arabic{section}.\arabic{subsection}}
\renewcommand{\thesubsubsection}{\arabic{section}.\arabic{subsection}.\arabic{subsubsection}}
\titleformat{\section}{\centering\heiti\zihao{3}}{第\thesection 章}{1em}{}
\titleformat{\subsection}{\heiti\zihao{4}}{\thesubsection}{1em}{}
\titleformat{\subsubsection}{\heiti\zihao{4}}{\thesubsubsection}{1em}{}
\newcommand{\tocfont}{\rmfamily\heiti\zihao{4}}
\titlecontents{section}[0pt]{\tocfont}{\makebox[4em][l]{第\thecontentslabel 章}}{}{\titlerule*[0.6pc]{.}\contentspage}
\titlecontents{subsection}[2em]{\tocfont}{\makebox[3em][l]{\thecontentslabel}}{}{\titlerule*[0.6pc]{.}\contentspage}
% 三级编号最长为 2.10.4，扩大编号栏以避免标题与编号相叠。
\titlecontents{subsubsection}[4em]{\tocfont}{\makebox[4em][l]{\thecontentslabel}}{}{\titlerule*[0.6pc]{.}\contentspage}
\setcounter{tocdepth}{3}
\RequirePackage{hyperref}
\hypersetup{colorlinks=true,linkcolor=black,citecolor=black,urlcolor=black,pdfborder={0 0 0}}
\RequirePackage[sort&compress]{gbt7714}
\renewcommand{\bibsection}{%
  \clearpage\phantomsection
  \noindent{\heiti\zihao{3}参考文献}\par\vspace{0.5em}
  \addcontentsline{toc}{section}{参考文献}}
\RequirePackage{fancyhdr}
\RequirePackage{xcolor}
\fancypagestyle{nopagenumber}{\fancyhf{}\renewcommand{\headrulewidth}{0pt}\renewcommand{\footrulewidth}{0pt}}
\newcommand{\cgscpageref}[1]{\begingroup\hypersetup{hidelinks}\pageref{#1}\endgroup}
\fancypagestyle{mainpagenumber}{%
  \fancyhf{}
  \fancyfoot[C]{{\color{gray}\songti\zihao{5}第 \thepage 页\quad 共 \cgscpageref{cgscLastPage} 页}}
  \renewcommand{\headrulewidth}{0pt}\renewcommand{\footrulewidth}{0pt}}
\newcommand{\frontmatter}{\hypersetup{pageanchor=false}\pagenumbering{gobble}\pagestyle{nopagenumber}}
\newcommand{\mainmatter}{\clearpage\pagenumbering{arabic}\setcounter{page}{1}\hypersetup{pageanchor=true}\pagestyle{mainpagenumber}}
\newcommand{\cgscLastPage}{\label{cgscLastPage}}
\endinput
